/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tajo.master;

import com.google.common.base.Preconditions;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.service.AbstractService;
import org.apache.tajo.QueryId;
import org.apache.tajo.QueryIdFactory;
import org.apache.tajo.TajoProtos;
import org.apache.tajo.algebra.Expr;
import org.apache.tajo.catalog.*;
import org.apache.tajo.catalog.exception.AlreadyExistsTableException;
import org.apache.tajo.catalog.exception.NoSuchTableException;
import org.apache.tajo.catalog.partition.PartitionMethodDesc;
import org.apache.tajo.catalog.statistics.TableStats;
import org.apache.tajo.conf.TajoConf;
import org.apache.tajo.engine.exception.IllegalQueryStatusException;
import org.apache.tajo.engine.exception.VerifyException;
import org.apache.tajo.engine.parser.HiveConverter;
import org.apache.tajo.engine.parser.SQLAnalyzer;
import org.apache.tajo.engine.planner.*;
import org.apache.tajo.engine.planner.logical.*;
import org.apache.tajo.engine.query.QueryContext;
import org.apache.tajo.ipc.ClientProtos;
import org.apache.tajo.master.TajoMaster.MasterContext;
import org.apache.tajo.master.querymaster.QueryInfo;
import org.apache.tajo.master.querymaster.QueryJobManager;
import org.apache.tajo.storage.AbstractStorageManager;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import static org.apache.tajo.ipc.ClientProtos.GetQueryStatusResponse;

public class GlobalEngine extends AbstractService {
  /** Class Logger */
  private final static Log LOG = LogFactory.getLog(GlobalEngine.class);

  private final MasterContext context;
  private final AbstractStorageManager sm;

  private SQLAnalyzer analyzer;
  private HiveConverter converter;
  private CatalogService catalog;
  private PreLogicalPlanVerifier preVerifier;
  private LogicalPlanner planner;
  private LogicalOptimizer optimizer;
  private LogicalPlanVerifier annotatedPlanVerifier;
  private DistributedQueryHookManager hookManager;

  public GlobalEngine(final MasterContext context) {
    super(GlobalEngine.class.getName());
    this.context = context;
    this.catalog = context.getCatalog();
    this.sm = context.getStorageManager();
  }

  public void start() {
    try  {
      analyzer = new SQLAnalyzer();
      converter = new HiveConverter();
      preVerifier = new PreLogicalPlanVerifier(context.getCatalog());
      planner = new LogicalPlanner(context.getCatalog());
      optimizer = new LogicalOptimizer(context.getConf());
      annotatedPlanVerifier = new LogicalPlanVerifier(context.getConf(), context.getCatalog());

      hookManager = new DistributedQueryHookManager();
      hookManager.addHook(new CreateTableHook());
      hookManager.addHook(new InsertHook());
    } catch (Throwable t) {
      LOG.error(t.getMessage(), t);
    }
    super.start();
  }

  public void stop() {
    super.stop();
  }

  public GetQueryStatusResponse executeQuery(String sql)
      throws InterruptedException, IOException, IllegalQueryStatusException {

    LOG.info("SQL: " + sql);
    QueryContext queryContext = new QueryContext();

    try {
      // setting environment variables
      String [] cmds = sql.split(" ");
      if(cmds != null) {
          if(cmds[0].equalsIgnoreCase("set")) {
              String[] params = cmds[1].split("=");
              context.getConf().set(params[0], params[1]);
              GetQueryStatusResponse.Builder responseBuilder = GetQueryStatusResponse.newBuilder();
              responseBuilder.setQueryId(QueryIdFactory.NULL_QUERY_ID.getProto());
              responseBuilder.setResultCode(ClientProtos.ResultCode.OK);
              responseBuilder.setState(TajoProtos.QueryState.QUERY_SUCCEEDED);
              return responseBuilder.build();
          }
      }

      final boolean hiveQueryMode = context.getConf().getBoolVar(TajoConf.ConfVars.HIVE_QUERY_MODE);
      LOG.info("hive.query.mode:" + hiveQueryMode);

      if (hiveQueryMode) {
        context.getSystemMetrics().counter("Query", "numHiveMode").inc();
        queryContext.setHiveQueryMode();
      }

      context.getSystemMetrics().counter("Query", "totalQuery").inc();

      Expr planningContext = hiveQueryMode ? converter.parse(sql) : analyzer.parse(sql);
      LogicalPlan plan = createLogicalPlan(planningContext);
      LogicalRootNode rootNode = plan.getRootBlock().getRoot();

      GetQueryStatusResponse.Builder responseBuilder = GetQueryStatusResponse.newBuilder();
      if (PlannerUtil.checkIfDDLPlan(rootNode)) {
        context.getSystemMetrics().counter("Query", "numDDLQuery").inc();
        updateQuery(rootNode.getChild());
        responseBuilder.setQueryId(QueryIdFactory.NULL_QUERY_ID.getProto());
        responseBuilder.setResultCode(ClientProtos.ResultCode.OK);
        responseBuilder.setState(TajoProtos.QueryState.QUERY_SUCCEEDED);
      } else {
        context.getSystemMetrics().counter("Query", "numDMLQuery").inc();
        hookManager.doHooks(queryContext, plan);

        QueryJobManager queryJobManager = this.context.getQueryJobManager();
        QueryInfo queryInfo;

        queryInfo = queryJobManager.createNewQueryJob(queryContext, sql, rootNode);

        if(queryInfo == null) {
          responseBuilder.setQueryId(QueryIdFactory.NULL_QUERY_ID.getProto());
          responseBuilder.setResultCode(ClientProtos.ResultCode.ERROR);
          responseBuilder.setState(TajoProtos.QueryState.QUERY_ERROR);
          responseBuilder.setErrorMessage("Fail starting QueryMaster.");
        } else {
          responseBuilder.setQueryId(queryInfo.getQueryId().getProto());
          responseBuilder.setResultCode(ClientProtos.ResultCode.OK);
          responseBuilder.setState(queryInfo.getQueryState());
          if(queryInfo.getQueryMasterHost() != null) {
            responseBuilder.setQueryMasterHost(queryInfo.getQueryMasterHost());
          }
          responseBuilder.setQueryMasterPort(queryInfo.getQueryMasterClientPort());
        }
      }
      GetQueryStatusResponse response = responseBuilder.build();

      return response;
    } catch (Throwable t) {
      context.getSystemMetrics().counter("Query", "errorQuery").inc();
      LOG.error("\nStack Trace:\n" + StringUtils.stringifyException(t));
      GetQueryStatusResponse.Builder responseBuilder = GetQueryStatusResponse.newBuilder();
      responseBuilder.setQueryId(QueryIdFactory.NULL_QUERY_ID.getProto());
      responseBuilder.setResultCode(ClientProtos.ResultCode.ERROR);
      responseBuilder.setState(TajoProtos.QueryState.QUERY_ERROR);
      String errorMessage = t.getMessage();
      if (t.getMessage() == null) {
        errorMessage = StringUtils.stringifyException(t);
      }
      responseBuilder.setErrorMessage(errorMessage);
      return responseBuilder.build();
    }
  }

  public String explainQuery(String sql) throws IOException, SQLException, PlanningException {
    LOG.info("SQL: " + sql);
    // parse the query

    final boolean hiveQueryMode = context.getConf().getBoolVar(TajoConf.ConfVars.HIVE_QUERY_MODE);
    Expr planningContext = hiveQueryMode ? converter.parse(sql) : analyzer.parse(sql);
    LOG.info("hive.query.mode:" + hiveQueryMode);

    LogicalPlan plan = createLogicalPlan(planningContext);
    return plan.toString();
  }

  public QueryId updateQuery(String sql) throws IOException, SQLException, PlanningException {
    LOG.info("SQL: " + sql);
    // parse the query
    Expr expr = analyzer.parse(sql);
    LogicalPlan plan = createLogicalPlan(expr);
    LogicalRootNode rootNode = plan.getRootBlock().getRoot();

    if (!PlannerUtil.checkIfDDLPlan(rootNode)) {
      throw new SQLException("This is not update query:\n" + sql);
    } else {
      updateQuery(rootNode.getChild());
      return QueryIdFactory.NULL_QUERY_ID;
    }
  }

  private boolean updateQuery(LogicalNode root) throws IOException {

    switch (root.getType()) {
      case CREATE_TABLE:
        CreateTableNode createTable = (CreateTableNode) root;
        createTable(createTable);
        return true;
      case DROP_TABLE:
        DropTableNode dropTable = (DropTableNode) root;
        dropTable(dropTable.getTableName(), dropTable.isPurge());
        return true;

      default:
        throw new InternalError("updateQuery cannot handle such query: \n" + root.toJson());
    }
  }

  private LogicalPlan createLogicalPlan(Expr expression) throws PlanningException {

    VerificationState state = new VerificationState();
    preVerifier.visit(state, new Stack<Expr>(), expression);
    if (!state.verified()) {
      StringBuilder sb = new StringBuilder();
      for (String error : state.getErrorMessages()) {
        sb.append(error).append("\n");
      }
      throw new VerifyException(sb.toString());
    }

    LogicalPlan plan = planner.createPlan(expression);
    LOG.info("=============================================");
    LOG.info("Non Optimized Query: \n" + plan.toString());
    LOG.info("=============================================");
    optimizer.optimize(plan);
    LOG.info("=============================================");
    LOG.info("Optimized Query: \n" + plan.toString());
    LOG.info("=============================================");

    annotatedPlanVerifier.visit(state, plan, plan.getRootBlock());

    if (!state.verified()) {
      StringBuilder sb = new StringBuilder();
      for (String error : state.getErrorMessages()) {
        sb.append(error).append("\n");
      }
      throw new VerifyException(sb.toString());
    }

    return plan;
  }

  private TableDesc createTable(CreateTableNode createTable) throws IOException {
    TableMeta meta;

    if (createTable.hasOptions()) {
      meta = CatalogUtil.newTableMeta(createTable.getStorageType(), createTable.getOptions());
    } else {
      meta = CatalogUtil.newTableMeta(createTable.getStorageType());
    }

    if(createTable.isExternal()){
      Preconditions.checkState(createTable.hasPath(), "ERROR: LOCATION must be given.");
    } else {
      Path tablePath = new Path(sm.getWarehouseDir(), createTable.getTableName().toLowerCase());
      createTable.setPath(tablePath);
    }

    return createTableOnPath(createTable.getTableName(), createTable.getTableSchema(), meta,
        createTable.getPath(), !createTable.isExternal(), createTable.getPartitionMethod());
  }

  public TableDesc createTableOnPath(String tableName, Schema schema, TableMeta meta,
                                     Path path, boolean isCreated, PartitionMethodDesc partitionDesc)
      throws IOException {
    if (catalog.existsTable(tableName)) {
      throw new AlreadyExistsTableException(tableName);
    }

    FileSystem fs = path.getFileSystem(context.getConf());

    if (isCreated) {
      fs.mkdirs(path);
    }

    if(!fs.exists(path)) {
      LOG.error("ERROR: " + path.toUri() + " does not exist");
      throw new IOException("ERROR: " + path.toUri() + " does not exist");
    }

    long totalSize = 0;

    try {
      totalSize = sm.calculateSize(path);
    } catch (IOException e) {
      LOG.warn("Cannot calculate the size of the relation", e);
    }

    TableStats stats = new TableStats();
    stats.setNumBytes(totalSize);
    TableDesc desc = CatalogUtil.newTableDesc(tableName, schema, meta, path);
    desc.setStats(stats);
    if (partitionDesc != null) {
      desc.setPartitionMethod(partitionDesc);
    }
    catalog.addTable(desc);

    LOG.info("Table " + desc.getName() + " is created (" + desc.getStats().getNumBytes() + ")");

    return desc;
  }

  /**
   * Drop a given named table
   *
   * @param tableName to be dropped
   * @param purge Remove all data if purge is true.
   */
  public void dropTable(String tableName, boolean purge) {
    CatalogService catalog = context.getCatalog();

    if (!catalog.existsTable(tableName)) {
      throw new NoSuchTableException(tableName);
    }

    Path path = catalog.getTableDesc(tableName).getPath();
    catalog.deleteTable(tableName);

    if (purge) {
      try {
        FileSystem fs = path.getFileSystem(context.getConf());
        fs.delete(path, true);
      } catch (IOException e) {
        throw new InternalError(e.getMessage());
      }
    }

    LOG.info("Table \"" + tableName + "\" is " + (purge ? " purged." : " dropped."));
  }

  public interface DistributedQueryHook {
    boolean isEligible(QueryContext queryContext, LogicalPlan plan);
    void hook(QueryContext queryContext, LogicalPlan plan) throws Exception;
  }

  public static class DistributedQueryHookManager {
    private List<DistributedQueryHook> hooks = new ArrayList<DistributedQueryHook>();
    public void addHook(DistributedQueryHook hook) {
      hooks.add(hook);
    }

    public void doHooks(QueryContext queryContext, LogicalPlan plan) {
      for (DistributedQueryHook hook : hooks) {
        if (hook.isEligible(queryContext, plan)) {
          try {
            hook.hook(queryContext, plan);
          } catch (Throwable t) {
            t.printStackTrace();
          }
        }
      }
    }
  }

  public class CreateTableHook implements DistributedQueryHook {

    @Override
    public boolean isEligible(QueryContext queryContext, LogicalPlan plan) {
      LogicalRootNode rootNode = plan.getRootBlock().getRoot();
      return rootNode.getChild().getType() == NodeType.CREATE_TABLE;
    }

    @Override
    public void hook(QueryContext queryContext, LogicalPlan plan) throws Exception {
      LogicalRootNode rootNode = plan.getRootBlock().getRoot();
      CreateTableNode createTableNode = rootNode.getChild();
      String tableName = createTableNode.getTableName();
      queryContext.setOutputTable(tableName);
      queryContext.setOutputPath(new Path(TajoConf.getWarehouseDir(context.getConf()), tableName));
      if(createTableNode.getPartitionMethod() != null) {
        queryContext.setPartitionMethod(createTableNode.getPartitionMethod());
      }
      queryContext.setCreateTable();
    }
  }

  public static class InsertHook implements DistributedQueryHook {

    @Override
    public boolean isEligible(QueryContext queryContext, LogicalPlan plan) {
      return plan.getRootBlock().getRootType() == NodeType.INSERT;
    }

    @Override
  public void hook(QueryContext queryContext, LogicalPlan plan) throws Exception {
      queryContext.setInsert();

      InsertNode insertNode = plan.getRootBlock().getNode(NodeType.INSERT);

      // Set QueryContext settings, such as output table name and output path.
      // It also remove data files if overwrite is true.
      Path outputPath;
      if (insertNode.hasTargetTable()) { // INSERT INTO [TB_NAME]
        queryContext.setOutputTable(insertNode.getTableName());
        queryContext.setOutputPath(insertNode.getPath());
      } else { // INSERT INTO LOCATION ...
        // When INSERT INTO LOCATION, must not set output table.
        outputPath = insertNode.getPath();
        queryContext.setFileOutput();
        queryContext.setOutputPath(outputPath);
      }

      if (insertNode.isOverwrite()) {
        queryContext.setOutputOverwrite();
      }
    }
  }
}
