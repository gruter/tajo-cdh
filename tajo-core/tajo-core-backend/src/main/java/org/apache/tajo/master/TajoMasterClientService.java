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

import com.google.protobuf.RpcController;
import com.google.protobuf.ServiceException;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.service.AbstractService;
import org.apache.tajo.QueryId;
import org.apache.tajo.QueryIdFactory;
import org.apache.tajo.TajoIdProtos;
import org.apache.tajo.TajoProtos;
import org.apache.tajo.catalog.*;
import org.apache.tajo.catalog.exception.NoSuchDatabaseException;
import org.apache.tajo.catalog.partition.PartitionMethodDesc;
import org.apache.tajo.catalog.proto.CatalogProtos;
import org.apache.tajo.conf.TajoConf;
import org.apache.tajo.conf.TajoConf.ConfVars;
import org.apache.tajo.ipc.ClientProtos;
import org.apache.tajo.ipc.ClientProtos.*;
import org.apache.tajo.ipc.TajoMasterClientProtocol;
import org.apache.tajo.ipc.TajoMasterClientProtocol.TajoMasterClientProtocolService;
import org.apache.tajo.master.TajoMaster.MasterContext;
import org.apache.tajo.master.querymaster.QueryInProgress;
import org.apache.tajo.master.querymaster.QueryInfo;
import org.apache.tajo.master.querymaster.QueryJobEvent;
import org.apache.tajo.master.querymaster.QueryJobManager;
import org.apache.tajo.master.rm.WorkerResource;
import org.apache.tajo.master.session.InvalidSessionException;
import org.apache.tajo.master.session.NoSuchSessionVariableException;
import org.apache.tajo.master.session.Session;
import org.apache.tajo.rpc.BlockingRpcServer;
import org.apache.tajo.rpc.protocolrecords.PrimitiveProtos;
import org.apache.tajo.rpc.protocolrecords.PrimitiveProtos.BoolProto;
import org.apache.tajo.rpc.protocolrecords.PrimitiveProtos.StringProto;
import org.apache.tajo.util.NetUtils;
import org.apache.tajo.util.ProtoUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;

import static org.apache.tajo.TajoConstants.DEFAULT_DATABASE_NAME;

public class TajoMasterClientService extends AbstractService {
  private final static Log LOG = LogFactory.getLog(TajoMasterClientService.class);
  private final MasterContext context;
  private final TajoConf conf;
  private final CatalogService catalog;
  private final TajoMasterClientProtocolServiceHandler clientHandler;
  private BlockingRpcServer server;
  private InetSocketAddress bindAddress;

  private final BoolProto BOOL_TRUE =
      BoolProto.newBuilder().setValue(true).build();
  private final BoolProto BOOL_FALSE =
      BoolProto.newBuilder().setValue(false).build();

  public TajoMasterClientService(MasterContext context) {
    super(TajoMasterClientService.class.getName());
    this.context = context;
    this.conf = context.getConf();
    this.catalog = context.getCatalog();
    this.clientHandler = new TajoMasterClientProtocolServiceHandler();
  }

  @Override
  public void start() {

    // start the rpc server
    String confClientServiceAddr = conf.getVar(ConfVars.TAJO_MASTER_CLIENT_RPC_ADDRESS);
    InetSocketAddress initIsa = NetUtils.createSocketAddr(confClientServiceAddr);
    int workerNum = conf.getIntVar(ConfVars.MASTER_SERVICE_RPC_SERVER_WORKER_THREAD_NUM);
    try {
      server = new BlockingRpcServer(TajoMasterClientProtocol.class, clientHandler, initIsa, workerNum);
    } catch (Exception e) {
      LOG.error(e);
      throw new RuntimeException(e);
    }
    server.start();

    bindAddress = NetUtils.getConnectAddress(server.getListenAddress());
    this.conf.setVar(ConfVars.TAJO_MASTER_CLIENT_RPC_ADDRESS, NetUtils.normalizeInetSocketAddress(bindAddress));
    LOG.info("Instantiated TajoMasterClientService at " + this.bindAddress);
    super.start();
  }

  @Override
  public void stop() {
    if (server != null) {
      server.shutdown();
    }
    super.stop();
  }

  public InetSocketAddress getBindAddress() {
    return this.bindAddress;
  }

  /////////////////////////////////////////////////////////////////////////////
  // TajoMasterClientProtocolService
  /////////////////////////////////////////////////////////////////////////////
  public class TajoMasterClientProtocolServiceHandler implements TajoMasterClientProtocolService.BlockingInterface {
    @Override
    public CreateSessionResponse createSession(RpcController controller, CreateSessionRequest request)
        throws ServiceException {
      try {
        // create a new session with base database name. If no database name is give, we use default database.
        String databaseName = request.hasBaseDatabaseName() ? request.getBaseDatabaseName() : DEFAULT_DATABASE_NAME;

        if (!context.getCatalog().existDatabase(databaseName)) {
          LOG.info("Session creation is canceled due to absent base database \"" + databaseName + "\".");
          throw new NoSuchDatabaseException(databaseName);
        }

        String sessionId =
            context.getSessionManager().createSession(request.getUsername(), databaseName);
        CreateSessionResponse.Builder builder = CreateSessionResponse.newBuilder();
        builder.setState(CreateSessionResponse.ResultState.SUCCESS);
        builder.setSessionId(TajoIdProtos.SessionIdProto.newBuilder().setId(sessionId).build());
        return builder.build();
      } catch (NoSuchDatabaseException nsde) {
        CreateSessionResponse.Builder builder = CreateSessionResponse.newBuilder();
        builder.setState(CreateSessionResponse.ResultState.FAILED);
        builder.setMessage(nsde.getMessage());
        return builder.build();
      } catch (InvalidSessionException e) {
        CreateSessionResponse.Builder builder = CreateSessionResponse.newBuilder();
        builder.setState(CreateSessionResponse.ResultState.FAILED);
        builder.setMessage(e.getMessage());
        return builder.build();
      }
    }

    @Override
    public BoolProto removeSession(RpcController controller, TajoIdProtos.SessionIdProto request)
        throws ServiceException {
      if (request != null) {
        context.getSessionManager().removeSession(request.getId());
      }
      return ProtoUtil.TRUE;
    }

    @Override
    public BoolProto updateSessionVariables(RpcController controller, UpdateSessionVariableRequest request)
        throws ServiceException {
      try {
        String sessionId = request.getSessionId().getId();
        for (CatalogProtos.KeyValueProto kv : request.getSetVariables().getKeyvalList()) {
          context.getSessionManager().setVariable(sessionId, kv.getKey(), kv.getValue());
        }
        for (String unsetVariable : request.getUnsetVariablesList()) {
          context.getSessionManager().removeVariable(sessionId, unsetVariable);
        }
        return ProtoUtil.TRUE;
      } catch (Throwable t) {
        throw new ServiceException(t);
      }
    }

    @Override
    public StringProto getSessionVariable(RpcController controller, SessionedStringProto request)
        throws ServiceException {

      try {
        return ProtoUtil.convertString(
            context.getSessionManager().getVariable(request.getSessionId().getId(), request.getValue()));
      } catch (Throwable t) {
        throw new ServiceException(t);
      }
    }

    @Override
    public BoolProto existSessionVariable(RpcController controller, SessionedStringProto request) throws ServiceException {
      try {
        String value = context.getSessionManager().getVariable(request.getSessionId().getId(), request.getValue());
        if (value != null) {
          return ProtoUtil.TRUE;
        } else {
          return ProtoUtil.FALSE;
        }
      } catch (NoSuchSessionVariableException nssv) {
        return ProtoUtil.FALSE;
      } catch (Throwable t) {
        throw new ServiceException(t);
      }
    }

    @Override
    public CatalogProtos.KeyValueSetProto getAllSessionVariables(RpcController controller,
                                                                 TajoIdProtos.SessionIdProto request)
        throws ServiceException {
      try {
        String sessionId = request.getId();
        Options options = new Options();
        options.putAll(context.getSessionManager().getAllVariables(sessionId));
        return options.getProto();
      } catch (Throwable t) {
        throw new ServiceException(t);
      }
    }

    @Override
    public StringProto getCurrentDatabase(RpcController controller, TajoIdProtos.SessionIdProto request)
        throws ServiceException {
      try {
        String sessionId = request.getId();
        return ProtoUtil.convertString(context.getSessionManager().getSession(sessionId).getCurrentDatabase());
      } catch (Throwable t) {
        throw new ServiceException(t);
      }
    }

    @Override
    public BoolProto selectDatabase(RpcController controller, SessionedStringProto request) throws ServiceException {
      try {
        String sessionId = request.getSessionId().getId();
        String databaseName = request.getValue();

        if (context.getCatalog().existDatabase(databaseName)) {
          context.getSessionManager().getSession(sessionId).selectDatabase(databaseName);
          return ProtoUtil.TRUE;
        } else {
          throw new ServiceException(new NoSuchDatabaseException(databaseName));
        }
      } catch (Throwable t) {
        throw new ServiceException(t);
      }
    }

    @Override
    public ExplainQueryResponse explainQuery(RpcController controller,
                                           SessionedStringProto request) throws ServiceException {

      try {
        Session session = context.getSessionManager().getSession(request.getSessionId().getId());

        if(LOG.isDebugEnabled()) {
          LOG.debug("ExplainQuery [" + request.getValue() + "]");
        }
        ClientProtos.ExplainQueryResponse.Builder responseBuilder = ClientProtos.ExplainQueryResponse.newBuilder();
        responseBuilder.setResultCode(ResultCode.OK);
        String plan = context.getGlobalEngine().explainQuery(session, request.getValue());
        if(LOG.isDebugEnabled()) {
          LOG.debug("ExplainQuery [" + plan + "]");
        }
        responseBuilder.setExplain(plan);
        return responseBuilder.build();
      } catch (Exception e) {
        LOG.error(e.getMessage(), e);
        ClientProtos.ExplainQueryResponse.Builder responseBuilder = ClientProtos.ExplainQueryResponse.newBuilder();
        responseBuilder.setResultCode(ResultCode.ERROR);
        responseBuilder.setErrorMessage(e.getMessage());
        return responseBuilder.build();
      }
    }
    @Override
    public GetQueryStatusResponse submitQuery(RpcController controller, QueryRequest request) throws ServiceException {


      try {
        Session session = context.getSessionManager().getSession(request.getSessionId().getId());

        if(LOG.isDebugEnabled()) {
          LOG.debug("Query [" + request.getQuery() + "] is submitted");
        }
        return context.getGlobalEngine().executeQuery(session, request.getQuery());
      } catch (Exception e) {
        LOG.error(e.getMessage(), e);
        ClientProtos.GetQueryStatusResponse.Builder responseBuilder = ClientProtos.GetQueryStatusResponse.newBuilder();
        responseBuilder.setResultCode(ResultCode.ERROR);
        if (e.getMessage() != null) {
          responseBuilder.setErrorMessage(ExceptionUtils.getStackTrace(e));
        } else {
          responseBuilder.setErrorMessage("Internal Error");
        }
        return responseBuilder.build();
      }
    }

    @Override
    public UpdateQueryResponse updateQuery(RpcController controller, QueryRequest request) throws ServiceException {

      try {
        Session session = context.getSessionManager().getSession(request.getSessionId().getId());
        UpdateQueryResponse.Builder builder = UpdateQueryResponse.newBuilder();
        try {
          context.getGlobalEngine().updateQuery(session, request.getQuery());
          builder.setResultCode(ResultCode.OK);
          return builder.build();
        } catch (Exception e) {
          builder.setResultCode(ResultCode.ERROR);
          if (e.getMessage() == null) {
            builder.setErrorMessage(ExceptionUtils.getStackTrace(e));
          }
          return builder.build();
        }
      } catch (Throwable t) {
        throw new ServiceException(t);
      }
    }

    @Override
    public GetQueryResultResponse getQueryResult(RpcController controller,
                                                 GetQueryResultRequest request) throws ServiceException {
      try {
        context.getSessionManager().touch(request.getSessionId().getId());
        QueryId queryId = new QueryId(request.getQueryId());
        QueryInProgress queryInProgress = context.getQueryJobManager().getQueryInProgress(queryId);
        QueryInfo queryInfo = queryInProgress.getQueryInfo();
        GetQueryResultResponse.Builder builder
            = GetQueryResultResponse.newBuilder();

        try {
          //TODO After implementation Tajo's user security feature, Should be modified.
          builder.setTajoUserName(UserGroupInformation.getCurrentUser().getUserName());
        } catch (IOException e) {
          LOG.warn("Can't get current user name");
        }
        switch (queryInfo.getQueryState()) {
          case QUERY_SUCCEEDED:
            // TODO check this logic needed
            //builder.setTableDesc((TableDescProto) queryJobManager.getResultDesc().getProto());
            break;
          case QUERY_FAILED:
          case QUERY_ERROR:
            builder.setErrorMessage("Query " + queryId + " is failed");
          default:
            builder.setErrorMessage("Query " + queryId + " is still running");
        }

        return builder.build();
      } catch (Throwable t) {
        throw new ServiceException(t);
      }
    }

    @Override
    public GetQueryListResponse getRunningQueryList(RpcController controller, GetQueryListRequest request)

        throws ServiceException {

      try {
        context.getSessionManager().touch(request.getSessionId().getId());
        GetQueryListResponse.Builder builder= GetQueryListResponse.newBuilder();

        Collection<QueryInProgress> queries
          = context.getQueryJobManager().getRunningQueries();

        BriefQueryInfo.Builder infoBuilder = BriefQueryInfo.newBuilder();

        for (QueryInProgress queryInProgress : queries) {
          QueryInfo queryInfo = queryInProgress.getQueryInfo();

          infoBuilder.setQueryId(queryInfo.getQueryId().getProto());
          infoBuilder.setState(queryInfo.getQueryState());
          infoBuilder.setQuery(queryInfo.getSql());
          infoBuilder.setStartTime(queryInfo.getStartTime());
          long endTime = (queryInfo.getFinishTime() == 0) ?
                         System.currentTimeMillis() : queryInfo.getFinishTime();
          infoBuilder.setFinishTime(endTime);
          infoBuilder.setProgress(queryInfo.getProgress());
          infoBuilder.setQueryMasterPort(queryInfo.getQueryMasterPort());
          infoBuilder.setQueryMasterHost(queryInfo.getQueryMasterHost());

          builder.addQueryList(infoBuilder.build());
        }

        GetQueryListResponse result = builder.build();
        return result;
      } catch (Throwable t) {
        throw new ServiceException(t);
      }
    }

    @Override
    public GetQueryListResponse getFinishedQueryList(RpcController controller, GetQueryListRequest request)
        throws ServiceException {

      try {
        context.getSessionManager().touch(request.getSessionId().getId());
        GetQueryListResponse.Builder builder = GetQueryListResponse.newBuilder();

        Collection<QueryInProgress> queries
            = context.getQueryJobManager().getFinishedQueries();

        BriefQueryInfo.Builder infoBuilder = BriefQueryInfo.newBuilder();

        for (QueryInProgress queryInProgress : queries) {
          QueryInfo queryInfo = queryInProgress.getQueryInfo();

          infoBuilder.setQueryId(queryInfo.getQueryId().getProto());
          infoBuilder.setState(queryInfo.getQueryState());
          infoBuilder.setQuery(queryInfo.getSql());
          infoBuilder.setStartTime(queryInfo.getStartTime());
          long endTime = (queryInfo.getFinishTime() == 0) ?
              System.currentTimeMillis() : queryInfo.getFinishTime();
          infoBuilder.setFinishTime(endTime);
          infoBuilder.setProgress(queryInfo.getProgress());
          infoBuilder.setQueryMasterPort(queryInfo.getQueryMasterPort());
          infoBuilder.setQueryMasterHost(queryInfo.getQueryMasterHost());

          builder.addQueryList(infoBuilder.build());
        }

        GetQueryListResponse result = builder.build();
        return result;
      } catch (Throwable t) {
        throw new ServiceException(t);
      }
    }

    @Override
    public GetQueryStatusResponse getQueryStatus(RpcController controller,
                                                 GetQueryStatusRequest request)
        throws ServiceException {

      try {
        context.getSessionManager().touch(request.getSessionId().getId());

        GetQueryStatusResponse.Builder builder = GetQueryStatusResponse.newBuilder();
        QueryId queryId = new QueryId(request.getQueryId());
        builder.setQueryId(request.getQueryId());

        if (queryId.equals(QueryIdFactory.NULL_QUERY_ID)) {
          builder.setResultCode(ResultCode.OK);
          builder.setState(TajoProtos.QueryState.QUERY_SUCCEEDED);
        } else {
          QueryInProgress queryInProgress = context.getQueryJobManager().getQueryInProgress(queryId);
          if (queryInProgress != null) {
            QueryInfo queryInfo = queryInProgress.getQueryInfo();
            builder.setResultCode(ResultCode.OK);
            builder.setState(queryInfo.getQueryState());
            builder.setProgress(queryInfo.getProgress());
            builder.setSubmitTime(queryInfo.getStartTime());
            if(queryInfo.getQueryMasterHost() != null) {
              builder.setQueryMasterHost(queryInfo.getQueryMasterHost());
              builder.setQueryMasterPort(queryInfo.getQueryMasterClientPort());
            }
            //builder.setInitTime(queryJobManager.getInitializationTime());
            //builder.setHasResult(!queryJobManager.isCreateTableStmt());
            if (queryInfo.getQueryState() == TajoProtos.QueryState.QUERY_SUCCEEDED) {
              builder.setFinishTime(queryInfo.getFinishTime());
            } else {
              builder.setFinishTime(System.currentTimeMillis());
            }
          } else {
            builder.setResultCode(ResultCode.ERROR);
            builder.setErrorMessage("No such query: " + queryId.toString());
          }
        }
        return builder.build();

      } catch (Throwable t) {
        throw new  ServiceException(t);
      }
    }

    /**
     * It is invoked by TajoContainerProxy.
     */
    @Override
    public BoolProto killQuery(RpcController controller, KillQueryRequest request) throws ServiceException {
      try {
        context.getSessionManager().touch(request.getSessionId().getId());
        QueryId queryId = new QueryId(request.getQueryId());
        QueryJobManager queryJobManager = context.getQueryJobManager();
        queryJobManager.getEventHandler().handle(new QueryJobEvent(QueryJobEvent.Type.QUERY_JOB_KILL,
            new QueryInfo(queryId)));
        return BOOL_TRUE;
      } catch (Throwable t) {
        throw new ServiceException(t);
      }
    }

    @Override
    public GetClusterInfoResponse getClusterInfo(RpcController controller,
                                                 GetClusterInfoRequest request)
        throws ServiceException {
      GetClusterInfoResponse.Builder builder
          = GetClusterInfoResponse.newBuilder();

      Map<String, WorkerResource> workers
          = context.getResourceManager().getWorkers();

      List<String> wokerKeys = new ArrayList<String>(workers.keySet());
      Collections.sort(wokerKeys);

      int runningQueryMasterTasks = 0;

      WorkerResourceInfo.Builder workerBuilder
          = WorkerResourceInfo.newBuilder();

      for(WorkerResource eachWorker: workers.values()) {
        workerBuilder.setAllocatedHost(eachWorker.getAllocatedHost());
        workerBuilder.setDiskSlots(eachWorker.getDiskSlots());
        workerBuilder.setCpuCoreSlots(eachWorker.getCpuCoreSlots());
        workerBuilder.setMemoryMB(eachWorker.getMemoryMB());
        workerBuilder.setLastHeartbeat(eachWorker.getLastHeartbeat());
        workerBuilder.setUsedMemoryMB(eachWorker.getUsedMemoryMB());
        workerBuilder.setUsedCpuCoreSlots(eachWorker.getUsedCpuCoreSlots());
        workerBuilder.setUsedDiskSlots(eachWorker.getUsedDiskSlots());
        workerBuilder.setWorkerStatus(eachWorker.getWorkerStatus().toString());
        workerBuilder.setQueryMasterMode(eachWorker.isQueryMasterMode());
        workerBuilder.setTaskRunnerMode(eachWorker.isTaskRunnerMode());
        workerBuilder.setPeerRpcPort(eachWorker.getPeerRpcPort());
        workerBuilder.setQueryMasterPort(eachWorker.getQueryMasterPort());
        workerBuilder.setClientPort(eachWorker.getClientPort());
        workerBuilder.setPullServerPort(eachWorker.getPullServerPort());
        workerBuilder.setHttpPort(eachWorker.getHttpPort());
        workerBuilder.setMaxHeap(eachWorker.getMaxHeap());
        workerBuilder.setFreeHeap(eachWorker.getFreeHeap());
        workerBuilder.setTotalHeap(eachWorker.getTotalHeap());
        workerBuilder.setNumRunningTasks(eachWorker.getNumRunningTasks());
        workerBuilder.setNumQueryMasterTasks(eachWorker.getNumQueryMasterTasks());

        builder.addWorkerList(workerBuilder.build());
      }

      return builder.build();
    }

    @Override
    public BoolProto createDatabase(RpcController controller, SessionedStringProto request) throws ServiceException {
      try {
        Session session = context.getSessionManager().getSession(request.getSessionId().getId());
        if (context.getGlobalEngine().createDatabase(session, request.getValue(), null, false)) {
          return BOOL_TRUE;
        } else {
          return BOOL_FALSE;
        }
      } catch (Throwable e) {
        throw new ServiceException(e);
      }
    }

    @Override
    public BoolProto existDatabase(RpcController controller, SessionedStringProto request) throws ServiceException {
      try {
        context.getSessionManager().touch(request.getSessionId().getId());
        if (catalog.existDatabase(request.getValue())) {
          return BOOL_TRUE;
        } else {
          return BOOL_FALSE;
        }
      } catch (Throwable e) {
        throw new ServiceException(e);
      }
    }

    @Override
    public BoolProto dropDatabase(RpcController controller, SessionedStringProto request) throws ServiceException {
      try {
        Session session = context.getSessionManager().getSession(request.getSessionId().getId());

        if (context.getGlobalEngine().dropDatabase(session, request.getValue(), false)) {
          return BOOL_TRUE;
        } else {
          return BOOL_FALSE;
        }
      } catch (Throwable e) {
        throw new ServiceException(e);
      }
    }

    @Override
    public PrimitiveProtos.StringListProto getAllDatabases(RpcController controller, TajoIdProtos.SessionIdProto
        request) throws ServiceException {
      try {
        context.getSessionManager().touch(request.getId());
        return ProtoUtil.convertStrings(catalog.getAllDatabaseNames());
      } catch (Throwable e) {
        throw new ServiceException(e);
      }
    }

    @Override
    public BoolProto existTable(RpcController controller, SessionedStringProto request) throws ServiceException {
      try {
        Session session = context.getSessionManager().getSession(request.getSessionId().getId());

        String databaseName;
        String tableName;
        if (CatalogUtil.isFQTableName(request.getValue())) {
          String [] splitted = CatalogUtil.splitFQTableName(request.getValue());
          databaseName = splitted[0];
          tableName = splitted[1];
        } else {
          databaseName = session.getCurrentDatabase();
          tableName = request.getValue();
        }

        if (catalog.existsTable(databaseName, tableName)) {
          return BOOL_TRUE;
        } else {
          return BOOL_FALSE;
        }
      } catch (Throwable e) {
        throw new ServiceException(e);
      }
    }

    @Override
    public GetTableListResponse getTableList(RpcController controller,
                                             GetTableListRequest request) throws ServiceException {
      try {
        Session session = context.getSessionManager().getSession(request.getSessionId().getId());
        String databaseName;
        if (request.hasDatabaseName()) {
          databaseName = request.getDatabaseName();
        } else {
          databaseName = session.getCurrentDatabase();
        }
        Collection<String> tableNames = catalog.getAllTableNames(databaseName);
        GetTableListResponse.Builder builder = GetTableListResponse.newBuilder();
        builder.addAllTables(tableNames);
        return builder.build();
      } catch (Throwable t) {
        throw new ServiceException(t);
      }
    }

    @Override
    public TableResponse getTableDesc(RpcController controller, GetTableDescRequest request) throws ServiceException {
      try {
        Session session = context.getSessionManager().getSession(request.getSessionId().getId());

        String databaseName;
        String tableName;
        if (CatalogUtil.isFQTableName(request.getTableName())) {
          String [] splitted = CatalogUtil.splitFQTableName(request.getTableName());
          databaseName = splitted[0];
          tableName = splitted[1];
        } else {
          databaseName = session.getCurrentDatabase();
          tableName = request.getTableName();
        }

        if (catalog.existsTable(databaseName, tableName)) {
          return TableResponse.newBuilder()
              .setResultCode(ResultCode.OK)
              .setTableDesc(catalog.getTableDesc(databaseName, tableName).getProto())
              .build();
        } else {
          return TableResponse.newBuilder()
              .setResultCode(ResultCode.ERROR)
              .setErrorMessage("ERROR: no such a table: " + request.getTableName())
              .build();
        }
      } catch (Throwable t) {
        throw new ServiceException(t);
      }
    }

    @Override
    public TableResponse createExternalTable(RpcController controller, CreateTableRequest request)
        throws ServiceException {
      try {
        Session session = context.getSessionManager().getSession(request.getSessionId().getId());

        Path path = new Path(request.getPath());
        FileSystem fs = path.getFileSystem(conf);

        if (!fs.exists(path)) {
          throw new IOException("No such a directory: " + path);
        }

        Schema schema = new Schema(request.getSchema());
        TableMeta meta = new TableMeta(request.getMeta());
        PartitionMethodDesc partitionDesc = null;
        if (request.hasPartition()) {
          partitionDesc = new PartitionMethodDesc(request.getPartition());
        }

        TableDesc desc;
        try {
          desc = context.getGlobalEngine().createTableOnPath(session, request.getName(), schema,
              meta, path, true, partitionDesc, false);
        } catch (Exception e) {
          return TableResponse.newBuilder()
              .setResultCode(ResultCode.ERROR)
              .setErrorMessage(e.getMessage()).build();
        }

        return TableResponse.newBuilder()
            .setResultCode(ResultCode.OK)
            .setTableDesc(desc.getProto()).build();
      } catch (InvalidSessionException ise) {
        return TableResponse.newBuilder()
            .setResultCode(ResultCode.ERROR)
            .setErrorMessage(ise.getMessage()).build();
      } catch (IOException ioe) {
        return TableResponse.newBuilder()
            .setResultCode(ResultCode.ERROR)
            .setErrorMessage(ioe.getMessage()).build();
      }
    }

    @Override
    public BoolProto dropTable(RpcController controller, DropTableRequest dropTable) throws ServiceException {
      try {
        Session session = context.getSessionManager().getSession(dropTable.getSessionId().getId());
        context.getGlobalEngine().dropTable(session, dropTable.getName(), false, dropTable.getPurge());
        return BOOL_TRUE;
      } catch (Throwable t) {
        throw new ServiceException(t);
      }
    }

    @Override
    public FunctionResponse getFunctionList(RpcController controller, SessionedStringProto request)
        throws ServiceException {

      try {
        context.getSessionManager().touch(request.getSessionId().getId());

        String functionName = request.getValue();
        Collection<FunctionDesc> functions = catalog.getFunctions();

        List<CatalogProtos.FunctionDescProto> functionProtos = new ArrayList<CatalogProtos.FunctionDescProto>();

        for (FunctionDesc eachFunction: functions) {
          if (functionName == null || functionName.isEmpty()) {
            functionProtos.add(eachFunction.getProto());
          } else {
            if(functionName.equals(eachFunction.getSignature())) {
              functionProtos.add(eachFunction.getProto());
            }
          }
        }
        return FunctionResponse.newBuilder()
            .setResultCode(ResultCode.OK)
            .addAllFunctions(functionProtos)
            .build();
      } catch (Throwable t) {
        throw new ServiceException(t);
      }
    }
  }
}
