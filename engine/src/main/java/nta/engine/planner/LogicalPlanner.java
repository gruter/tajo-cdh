package nta.engine.planner;

import java.util.Set;
import java.util.Stack;

import nta.catalog.Column;
import nta.catalog.Schema;
import nta.catalog.SchemaUtil;
import nta.catalog.proto.CatalogProtos.DataType;
import nta.engine.Context;
import nta.engine.exec.eval.EvalNode;
import nta.engine.exec.eval.FieldEval;
import nta.engine.exec.eval.FuncCallEval;
import nta.engine.parser.CreateIndexStmt;
import nta.engine.parser.CreateTableStmt;
import nta.engine.parser.ParseTree;
import nta.engine.parser.QueryAnalyzer;
import nta.engine.parser.QueryBlock;
import nta.engine.parser.QueryBlock.FromTable;
import nta.engine.parser.QueryBlock.JoinClause;
import nta.engine.parser.QueryBlock.Target;
import nta.engine.planner.logical.CreateIndexNode;
import nta.engine.planner.logical.CreateTableNode;
import nta.engine.planner.logical.EvalExprNode;
import nta.engine.planner.logical.GroupbyNode;
import nta.engine.planner.logical.JoinNode;
import nta.engine.planner.logical.LogicalNode;
import nta.engine.planner.logical.LogicalRootNode;
import nta.engine.planner.logical.ProjectionNode;
import nta.engine.planner.logical.ScanNode;
import nta.engine.planner.logical.SelectionNode;
import nta.engine.planner.logical.SortNode;
import nta.engine.planner.logical.UnaryNode;
import nta.engine.query.exception.InvalidQueryException;
import nta.engine.query.exception.NotSupportQueryException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * This class creates a logical plan from a parse tree ({@link QueryBlock})
 * generated by {@link QueryAnalyzer}.
 * 
 * @author Hyunsik Choi
 *
 * @see QueryBlock
 */
public class LogicalPlanner {
  private static Log LOG = LogFactory.getLog(LogicalPlanner.class);

  private LogicalPlanner() {
  }

  /**
   * This generates a logical plan.
   * 
   * @param query a parse tree
   * @return a initial logical plan
   */
  public static LogicalNode createPlan(Context ctx, ParseTree query) {
    LogicalNode plan = null;
    
    switch(query.getType()) {
    case SELECT:
      LOG.info("Planning select statement");
      QueryBlock select = (QueryBlock) query;
      plan = buildSelectPlan(ctx, select);
      break;
      
    case CREATE_INDEX:
      LOG.info("Planning create index statement");
      CreateIndexStmt createIndex = (CreateIndexStmt) query;
      plan = buildCreateIndexPlan(ctx, createIndex);
      break;

    case CREATE_TABLE:
      LOG.info("Planning store statement");
      CreateTableStmt createTable = (CreateTableStmt) query;
      plan = buildCreateTablePlan(ctx, createTable);
      break;

    default:;
    throw new NotSupportQueryException(query.toString());
    }
    
    LogicalRootNode root = new LogicalRootNode();
    root.setInputSchema(plan.getOutputSchema());
    root.setOutputSchema(plan.getOutputSchema());
    root.setSubNode(plan);
    annotateInOutSchemas(ctx, root);
    
    return root;
  }
  
  private static LogicalNode buildCreateIndexPlan(Context ctx,
      CreateIndexStmt stmt) {    
    return new CreateIndexNode(stmt);    
  }
  
  private static LogicalNode buildCreateTablePlan(Context ctx, 
      CreateTableStmt query) {
    LogicalNode subNode = buildSelectPlan(ctx, query.getSelectStmt());
    
    CreateTableNode storeNode = new CreateTableNode(query.getTableName());
    storeNode.setInputSchema(subNode.getOutputSchema());
    storeNode.setOutputSchema(subNode.getOutputSchema());
    storeNode.setSubNode(subNode);
    
    return storeNode;
  }
  
  /**
   * ^(SELECT from_clause? where_clause? groupby_clause? selectList)
   * 
   * @param query
   * @return
   */
  private static LogicalNode buildSelectPlan(Context ctx, QueryBlock query) {
    LogicalNode subroot = null;
    if(query.hasFromClause()) {
      if (query.hasExplicitJoinClause()) {
        subroot = createExplicitJoinTree(ctx, query.getJoinClause());
      } else {
        subroot = createImplicitJoinTree(ctx, query.getFromTables());
      }
    } else {
      subroot = new EvalExprNode(query.getTargetList());
      return subroot;
    }
    
    if(query.hasWhereClause()) {
      SelectionNode selNode = 
          new SelectionNode(query.getWhereCondition());
      selNode.setSubNode(subroot);
      subroot = selNode;
    }
    
    if(query.hasAggregation()) {
      if (query.hasGroupbyClause()) {
        GroupbyNode groupbyNode = new GroupbyNode(query.getGroupFields());
        if(query.hasHavingCond())
          groupbyNode.setHavingCondition(query.getHavingCond());
        
        groupbyNode.setSubNode(subroot);
        subroot = groupbyNode;
      } else {
        // when aggregation functions are used without grouping fields
        GroupbyNode groupbyNode = new GroupbyNode(new Column [] {});
        groupbyNode.setSubNode(subroot);
        subroot = groupbyNode;
      }
    }
    
    if(query.hasOrderByClause()) {
      SortNode sortNode = new SortNode(query.getSortKeys());
      sortNode.setSubNode(subroot);
      subroot = sortNode;
    }
    
    if (!query.getProjectAll() && !query.hasGroupbyClause()) {
        ProjectionNode prjNode = new ProjectionNode(query.getTargetList());
        if (subroot != null) { // false if 'no from' statement
          prjNode.setSubNode(subroot);
        }
        subroot = prjNode;
    }
    
    return subroot;  
  }
  
  private static LogicalNode createExplicitJoinTree(Context ctx, 
      JoinClause joinClause) {
    JoinNode join = null;
    
    join = new JoinNode(joinClause.getJoinType(),
        new ScanNode(joinClause.getLeft()));
    if (joinClause.hasJoinQual()) {
      join.setJoinQual(joinClause.getJoinQual());
    } else if (joinClause.hasJoinColumns()) {
      // TODO - to be implemented. Now, tajo only support 'ON' join clause.
    }
    
    if (joinClause.hasRightJoin()) {
      join.setInner(createExplicitJoinTree(ctx, joinClause.getRightJoin()));
    } else {
      join.setInner(new ScanNode(joinClause.getRight()));
    }
    
    return join;
  }
  
  private static LogicalNode createImplicitJoinTree(Context ctx, 
      FromTable [] tables) {
    LogicalNode subroot = null;
    
    subroot = new ScanNode(tables[0]);
    Schema joinSchema = null;
    if(tables.length > 1) {    
      for(int i=1; i < tables.length; i++) {
        JoinNode join = new JoinNode(JoinType.INNER, 
            subroot, new ScanNode(tables[i]));
        joinSchema = SchemaUtil.merge(
            join.getOuterNode().getOutputSchema(),
            join.getInnerNode().getOutputSchema());
        join.setInputSchema(joinSchema);
        join.setOutputSchema(joinSchema);
        subroot = join;
      }
    }
    
    return subroot;
  }
  
  /**
   * This method determines the input and output schemas of all logical 
   * operators.
   * 
   * @param ctx
   * @param logicalPlan
   * @return a result target list 
   */
  public static void annotateInOutSchemas(Context ctx, 
      LogicalNode logicalPlan) {
    Stack<LogicalNode> stack = new Stack<LogicalNode>();
    refineInOutSchama(ctx, logicalPlan, null, stack);
  }
  
  static void getTargetListFromEvalTree(Schema inputSchema, 
      EvalNode evalTree, Set<Column> targetList) {
    
    switch(evalTree.getType()) {
    case FIELD:
      FieldEval fieldEval = (FieldEval) evalTree;
      Column col = inputSchema.getColumn(fieldEval.getName());
      targetList.add(col);
      
      break;
    
    case PLUS:
    case MINUS:
    case MULTIPLY:
    case DIVIDE:
    case AND:
    case OR:    
    case EQUAL:
    case NOT_EQUAL:
    case LTH:
    case LEQ:
    case GTH:   
    case GEQ:
      getTargetListFromEvalTree(inputSchema, evalTree.getLeftExpr(), targetList);
      getTargetListFromEvalTree(inputSchema, evalTree.getRightExpr(), targetList);
      
      break;
     case FUNCTION:
       FuncCallEval funcEval = (FuncCallEval) evalTree;
       for(EvalNode evalNode : funcEval.getGivenArgs()) {
         getTargetListFromEvalTree(inputSchema, evalNode, targetList);
       }
    default: return;
    }
  }
  
  static void refineInOutSchama(Context ctx,
      LogicalNode logicalNode, Set<Column> necessaryTargets, 
      Stack<LogicalNode> stack) {
    
    if (logicalNode == null) {
      return;
    }
    
    Schema inputSchema = null;
    Schema outputSchema = null;
    
    switch(logicalNode.getType()) {
    case ROOT:
      LogicalRootNode root = (LogicalRootNode) logicalNode;
      stack.push(root);
      refineInOutSchama(ctx, root.getSubNode(), necessaryTargets, stack);
      stack.pop();
      
      root.setInputSchema(root.getSubNode().getOutputSchema());
      root.setOutputSchema(root.getSubNode().getOutputSchema());
      break;
    
    case STORE:
      CreateTableNode storeNode = (CreateTableNode) logicalNode;
      stack.push(storeNode);
      refineInOutSchama(ctx, storeNode.getSubNode(), necessaryTargets, stack);
      stack.pop();
      inputSchema = storeNode.getSubNode().getOutputSchema();
      storeNode.setInputSchema(inputSchema);
      storeNode.setOutputSchema(inputSchema);
      break;
      
    case PROJECTION:
      ProjectionNode projNode = ((ProjectionNode)logicalNode);
      if(necessaryTargets != null) {
        if(projNode.isAll()) {
          for(Column column : projNode.getOutputSchema().getColumns()) {
            necessaryTargets.add(column);
          }          
        } else {
          for(Target t : projNode.getTargetList()) {
            getTargetListFromEvalTree(projNode.getInputSchema(), t.getEvalTree(), 
                necessaryTargets);
          }
        }
        
        stack.push(projNode);
        refineInOutSchama(ctx, projNode.getSubNode(), necessaryTargets, stack);
        stack.pop();
        
        LogicalNode parent = stack.peek();
        if(parent instanceof UnaryNode) {
          ((UnaryNode) parent).setSubNode(((UnaryNode) projNode).getSubNode());
        } else {
          throw new InvalidQueryException("Unexpected Logical Query Plan");
        }
      } else {
        stack.push(projNode);
        refineInOutSchama(ctx, projNode.getSubNode(), necessaryTargets, stack);
        stack.pop();
      }
      
      if (projNode.getSubNode() != null)
        projNode.setInputSchema(projNode.getSubNode().getOutputSchema());
      
      if(projNode.isAll()) {
        projNode.setOutputSchema(projNode.getInputSchema());  
      } else {
        Schema prjTargets = new Schema();
        for(Target t : projNode.getTargetList()) {
          DataType type = t.getEvalTree().getValueType();
          String name = t.getEvalTree().getName();
          prjTargets.addColumn(name,type);
        }
        projNode.setOutputSchema(prjTargets);
      }
      
      break;
      
    case SELECTION:
      SelectionNode selNode = ((SelectionNode)logicalNode);
      if(necessaryTargets != null) { // optimization phase
        getTargetListFromEvalTree(selNode.getInputSchema(), selNode.getQual(), 
            necessaryTargets);
      }
      stack.push(selNode);
      refineInOutSchama(ctx, selNode.getSubNode(), necessaryTargets, stack);
      stack.pop();
      inputSchema = selNode.getSubNode().getOutputSchema();
      selNode.setInputSchema(inputSchema);
      selNode.setOutputSchema(inputSchema);
      
      break;
      
    case GROUP_BY:
      GroupbyNode groupByNode = ((GroupbyNode)logicalNode);
      
      if(necessaryTargets != null) { // projection push phase
        if(groupByNode.hasHavingCondition()) {
          getTargetListFromEvalTree(groupByNode.getInputSchema(),
              groupByNode.getHavingCondition(), necessaryTargets);
        }
        
        for(Column grpField : groupByNode.getGroupingColumns()) {
          necessaryTargets.add(grpField);
        }
        
/*        Target [] grpTargetList = null;
        for(LogicalNode node : stack) {
          if(node.getType() == ExprType.PROJECTION) {
            ProjectionNode prjNode = (ProjectionNode) node;
            grpTargetList = prjNode.getTargetList();
          }
        }
        if(grpTargetList != null) {
          groupByNode.setTargetList(grpTargetList);
        }*/
        
        for (Target t : groupByNode.getTargetList()) {
          getTargetListFromEvalTree(groupByNode.getInputSchema(),
              t.getEvalTree(), necessaryTargets);
        }
      }
      stack.push(groupByNode);
      refineInOutSchama(ctx, groupByNode.getSubNode(), necessaryTargets, stack);
      stack.pop();
      groupByNode.setInputSchema(groupByNode.getSubNode().getOutputSchema());
      
      Schema grpTargets = new Schema();
      for(Target t : ctx.getTargetList()) {
        DataType type = t.getEvalTree().getValueType();
        String name = t.getEvalTree().getName();
        grpTargets.addColumn(name,type);
      }
      groupByNode.setTargetList(ctx.getTargetList());
      groupByNode.setOutputSchema(grpTargets);
      
      break;
      
    case SCAN:
      ScanNode scanNode = ((ScanNode)logicalNode);
      Schema scanSchema = 
          ctx.getTable(scanNode.getTableId()).getMeta().getSchema();
      Schema scanTargetList = new Schema();
      scanTargetList.addColumns(scanSchema);
      scanNode.setInputSchema(scanTargetList);
      
      if(necessaryTargets != null) { // projection push phase
        outputSchema = new Schema();
        for(Column col : scanTargetList.getColumns()) {
          if(necessaryTargets.contains(col)) {
            outputSchema.addColumn(col);
          }
        }
        scanNode.setOutputSchema(outputSchema);
        
        Schema projectedList = new Schema();
        if(scanNode.hasQual()) {
          getTargetListFromEvalTree(scanTargetList, scanNode.getQual(), 
              necessaryTargets);
        }
        for(Column col : scanTargetList.getColumns()) {
          if(necessaryTargets.contains(col)) {
            projectedList.addColumn(col);
          }
        }
        
        scanNode.setTargetList(projectedList);
      } else {
        scanNode.setOutputSchema(scanTargetList);
      }
      
      break;
      
    case SORT:
      SortNode sortNode = ((SortNode)logicalNode);
      // TODO - before this, should modify sort keys to eval trees.
      stack.push(sortNode);
      refineInOutSchama(ctx, sortNode.getSubNode(), necessaryTargets, stack);
      stack.pop();
      inputSchema = sortNode.getSubNode().getOutputSchema();
      sortNode.setInputSchema(inputSchema);
      sortNode.setOutputSchema(inputSchema);
      
      break;
    
    case JOIN:
      JoinNode joinNode = (JoinNode) logicalNode;
      stack.push(joinNode);
      refineInOutSchama(ctx, joinNode.getOuterNode(), necessaryTargets, 
          stack);
      refineInOutSchama(ctx, joinNode.getInnerNode(), necessaryTargets,
          stack);
      stack.pop();      
            
      break;
      default: return;
    }
  }
  

/*  public static class TargetList {
    NavigableMap<Integer,Column> targetList = new TreeMap<Integer,Column>();
=======
  public static class TargetList {
	  @Expose
//    NavigableMap<Integer,Column> targetList = new TreeMap<Integer,Column>();
	  Map<Integer,Column> targetList = new TreeMap<Integer,Column>();
	  @Expose
>>>>>>> 7737b3e378ec82c00f8dcd59a3880054dd131d90
    Map<String,Column> targetListByName = new HashMap<String, Column>();
	  @Expose
    private volatile int id = 0;
    
    public TargetList() {
    }
    
    public TargetList(TargetList targets) {
      this.targetList = new TreeMap<Integer, Column>(targets.targetList);
      this.targetListByName = new HashMap<String, Column>(targets.targetListByName);
      this.id = targets.id;
    }
    
    public void put(Column column) {
      int newId = id++;
      column.setId(newId);
      targetList.put(newId, column);
      targetListByName.put(column.getName(), column);
    }
    
    public void put(Column [] columns) {
      int newId;
      for(Column column : columns) {
        newId = id++;
        column.setId(newId);
        targetList.put(newId, column);
        targetListByName.put(column.getName(), column);
      }
    }
    
    public void put(Schema schema) {
      int newId;
      
      for(Column column : schema.getColumns()) {
        newId = id++;
        targetList.put(newId, column);
        targetListByName.put(column.getName(), column);
      }
    }
    
    public Column getColumn(String qualifiedName) {
      return targetListByName.get(qualifiedName);
    }
    
    public Collection<Column> getColumns() {
      return this.targetList.values();
    }
    
    public Schema toSchema() {
      Schema newSchema = new Schema();
      for(Entry<Integer,Column> entry : targetList.entrySet()) {
        newSchema.addColumn(entry.getValue());
      }
      return newSchema;
    }
    
    public String toString() {
      StringBuilder sb = new StringBuilder("[");
      Column col = null;
      int i=0;  
      for(Entry<Integer,Column> entry : targetList.entrySet()) {
        col = entry.getValue();
        sb.append("\"").append(col.getName());
        sb.append(" ").append(col.getDataType()).append("\"");
        if(i < targetList.size() - 1) {
          sb.append(",");
        }
        i++;
      }
      sb.append("]");
      return sb.toString();
    }
    
    public String toJSON() {
      Gson gson = GsonCreator.getInstance();
      return gson.toJson(this, LogicalPlanner.class);
    }
    
    public int size() {
      return targetListByName.size();
    }
    
    public Object clone() {
      return new TargetList(this);
    }
  }
  
  public static TargetList merge(TargetList left, TargetList right) {
    TargetList merged = new TargetList();
    for(Entry<Integer,Column> entry : left.targetList.entrySet()) {
      merged.put(entry.getValue());
    }
    for(Entry<Integer,Column> entry : right.targetList.entrySet()) {
      merged.put(entry.getValue());
    }
    
    return merged;
  }*/
}
