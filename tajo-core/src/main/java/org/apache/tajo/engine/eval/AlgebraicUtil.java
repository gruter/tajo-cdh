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

package org.apache.tajo.engine.eval;

import org.apache.tajo.catalog.Column;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AlgebraicUtil {
  
  /**
   * Transpose a given comparison expression into the expression 
   * where the variable corresponding to the target is placed 
   * on the left-hand side.
   * 
   * @param expr
   * @param target
   * @return Transposed expression
   */
  public static EvalNode transpose(EvalNode expr, Column target) {
    EvalNode commutated = null;
    // If the variable is in the right term, inverse the expr.
    if (!EvalTreeUtil.containColumnRef(expr.getLeftExpr(), target)) {
      // the commutate method works with a copy of the expr
      commutated = commutate(expr);
    } else {
      try {
        commutated = (EvalNode) expr.clone();
      } catch (CloneNotSupportedException e) {
        throw new AlgebraicException(e);
      }
    }

    return _transpose(commutated, target);
  }
  
  private static EvalNode _transpose(EvalNode _expr, Column target) {
     EvalNode expr = eliminateConstantExprs(_expr);
     
     if (isSingleVar(expr.getLeftExpr())) {
       return expr;
     }
     
     EvalNode left = expr.getLeftExpr();     
     EvalNode lTerm = null;
     EvalNode rTerm = null;
     
    if (left.getType() == EvalType.PLUS
        || left.getType() == EvalType.MINUS
        || left.getType() == EvalType.MULTIPLY
        || left.getType() == EvalType.DIVIDE) {
      
      // If the left-left term is a variable, the left-right term is transposed.
      if(EvalTreeUtil.containColumnRef(left.getLeftExpr(), target)) {
        PartialBinaryExpr tmpTerm = splitRightTerm(left);
        tmpTerm.type = inverseOperator(tmpTerm.type);
        tmpTerm.setLeftExpr(expr.getRightExpr());
        lTerm = left.getLeftExpr();
        rTerm = new BinaryEval(tmpTerm);
      } else { 
        // Otherwise, the left-right term is transposed into the left-left term.
        PartialBinaryExpr tmpTerm = splitLeftTerm(left);
        tmpTerm.type = inverseOperator(tmpTerm.type);
        tmpTerm.setLeftExpr(expr.getRightExpr());        
        lTerm = left.getRightExpr();
        rTerm = new BinaryEval(tmpTerm);    
      }
    }
    
    return _transpose(new BinaryEval(expr.getType(), lTerm, rTerm), target);
  }
  
  /**
   * Inverse a given operator (+, -, *, /)
   * 
   * @param type
   * @return inversed operator type
   */
  public static EvalType inverseOperator(EvalType type) {
    switch (type) {
    case PLUS:
      return EvalType.MINUS;
    case MINUS:
      return EvalType.PLUS;
    case MULTIPLY:
      return EvalType.DIVIDE;
    case DIVIDE:
      return EvalType.MULTIPLY;
    default : throw new AlgebraicException("ERROR: cannot inverse the operator: " 
      + type);
    }
  }
  
  /**
   * Examine if a given expr is a variable.
   * 
   * @param node
   * @return true if a given expr is a variable.
   */
  private static boolean isSingleVar(EvalNode node) {
    if (node.getType() == EvalType.FIELD) {
      return true;
    } else {
      return false;
    }
  }
  
  /**
   * Simplify the given expr. That is, all subexprs consisting of only constants
   * are calculated immediately.
   * 
   * @param expr to be simplified
   * @return the simplified expr
   */
  public static EvalNode eliminateConstantExprs(EvalNode expr) {

    if (expr.getType() == EvalType.FIELD) {
      return expr;
    }

    EvalNode left = expr.getLeftExpr();
    EvalNode right = expr.getRightExpr();
    
    switch (expr.getType()) {
    case AND:
    case OR:
    case EQUAL:
    case NOT_EQUAL:
    case LTH:
    case LEQ:
    case GTH:
    case GEQ:
    case PLUS:
    case MINUS:
    case MULTIPLY:
    case DIVIDE:
    case MODULAR:
      left = eliminateConstantExprs(left);
      right = eliminateConstantExprs(right);

      if (left.getType() == EvalType.CONST && right.getType() == EvalType.CONST) {
        return new ConstEval(expr.eval(null, null));
      } else {
        return new BinaryEval(expr.getType(), left, right);
      }

    case CONST:
      return expr;
      
    default: new AlgebraicException("Wrong expression: " + expr);
    }
    return expr;
  }
  
  /** 
   * @param expr to be evaluated if the expr includes one variable
   * @return true if expr has only one field
   */
  public static boolean containSingleVar(EvalNode expr) {
    Map<EvalType, Integer> counter = EvalTreeUtil.getExprCounters(expr);
    
    int sum = 0;
    for (Integer cnt : counter.values()) {      
      sum += cnt;
    }
    
    if (sum == 1 && counter.get(EvalType.FIELD) == 1) {
      return true;
    } else {
      return false;
    }
  }
  
  /**
   * Split the left term and transform it into the right deep expression.
   * 
   * @param expr - notice the left term of this expr will be eliminated 
   * after done.
   * @return the separated expression changed into the right deep expression.  
   * For example, the expr 'x * y' is transformed into '* x'.  
   *
   */
  public static PartialBinaryExpr splitLeftTerm(EvalNode expr) {
    
    if (!(expr.getType() == EvalType.PLUS
        || expr.getType() == EvalType.MINUS
        || expr.getType() == EvalType.MULTIPLY
        || expr.getType() == EvalType.DIVIDE)) {
      throw new AlgebraicException("Invalid algebraic operation: " + expr);
    }
    
    if (expr.getLeftExpr().getType() != EvalType.CONST) {
      return splitLeftTerm(expr.getLeftExpr());
    }
    
    PartialBinaryExpr splitted = 
        new PartialBinaryExpr(expr.getType(), null, expr.getLeftExpr());
    expr.setLeftExpr(null);
    return splitted;
  }
  
  /**
   * Split the left term and transform it into the right deep expression.
   * 
   * @param expr - to be splited
   * @return the separated expression changed into the right deep expression.
   * For example, the expr 'x * y' is transformed into '* y'. 
   *
   * @throws CloneNotSupportedException
   */
  public static PartialBinaryExpr splitRightTerm(EvalNode expr) {
    
    if (!(expr.getType() == EvalType.PLUS
        || expr.getType() == EvalType.MINUS
        || expr.getType() == EvalType.MULTIPLY
        || expr.getType() == EvalType.DIVIDE)) {
      throw new AlgebraicException("Invalid algebraic operation: " + expr);
    }
    
    if (expr.getRightExpr().getType() != EvalType.CONST) {
      return splitRightTerm(expr.getRightExpr());
    }
    
    PartialBinaryExpr splitted = 
        new PartialBinaryExpr(expr.getType(), null, expr.getRightExpr());
    expr.setRightExpr(null);
    return splitted;
  }
  
  /**
   * Commutate two terms which are added, subtracted and multiplied.
   * 
   * @param inputExpr
   * @return
   */
  public static EvalNode commutate(EvalNode inputExpr) {
    EvalNode expr;
    switch (inputExpr.getType()) {
    case AND:
    case OR:
    case EQUAL:
    case PLUS:
    case MINUS:
    case MULTIPLY: // these types can be commutated w/o any change
      expr = EvalTreeFactory.create(inputExpr.getType(),
          inputExpr.getRightExpr(), inputExpr.getLeftExpr());
      break;
      
    case GTH:
      expr = EvalTreeFactory.create(EvalType.LTH,
          inputExpr.getRightExpr(), inputExpr.getLeftExpr());
      break;
    case GEQ:
      expr = EvalTreeFactory.create(EvalType.LEQ,
          inputExpr.getRightExpr(), inputExpr.getLeftExpr());
      break;
    case LTH:
      expr = EvalTreeFactory.create(EvalType.GTH,
          inputExpr.getRightExpr(), inputExpr.getLeftExpr());
      break;
    case LEQ:
      expr = EvalTreeFactory.create(EvalType.GEQ,
          inputExpr.getRightExpr(), inputExpr.getLeftExpr());
      break;
      
    default :
      throw new AlgebraicException("Cannot commutate the expr: " + inputExpr);
    }
    
    return expr;
  }

  public static boolean isComparisonOperator(EvalNode expr) {
    return expr.getType() == EvalType.EQUAL ||
        expr.getType() == EvalType.LEQ ||
        expr.getType() == EvalType.LTH ||
        expr.getType() == EvalType.GEQ ||
        expr.getType() == EvalType.GTH ||
        expr.getType() == EvalType.BETWEEN;
  }

  public static boolean isIndexableOperator(EvalNode expr) {
    return expr.getType() == EvalType.EQUAL ||
        expr.getType() == EvalType.LEQ ||
        expr.getType() == EvalType.LTH ||
        expr.getType() == EvalType.GEQ ||
        expr.getType() == EvalType.GTH ||
        expr.getType() == EvalType.BETWEEN ||
        expr.getType() == EvalType.IN ||
        (expr.getType() == EvalType.LIKE && !((LikePredicateEval)expr).isLeadingWildCard());
  }

  /**
   * Convert a list of conjunctive normal forms into a singleton expression.
   *
   * @param cnfExprs
   * @return The EvalNode object that merges all CNF-formed expressions.
   */
  public static EvalNode createSingletonExprFromCNF(EvalNode... cnfExprs) {
    if (cnfExprs.length == 1) {
      return cnfExprs[0];
    }

    return createSingletonExprFromCNFRecursive(cnfExprs, 0);
  }

  private static EvalNode createSingletonExprFromCNFRecursive(EvalNode[] evalNode, int idx) {
    if (idx == evalNode.length - 2) {
      return new BinaryEval(EvalType.AND, evalNode[idx], evalNode[idx + 1]);
    } else {
      return new BinaryEval(EvalType.AND, evalNode[idx], createSingletonExprFromCNFRecursive(evalNode, idx + 1));
    }
  }

  /**
   * Transforms a expression to an array of conjunctive normal formed expressions.
   *
   * @param expr The expression to be transformed to an array of CNF-formed expressions.
   * @return An array of CNF-formed expressions
   */
  public static EvalNode [] toConjunctiveNormalFormArray(EvalNode expr) {
    List<EvalNode> list = new ArrayList<EvalNode>();
    toConjunctiveNormalFormArrayRecursive(expr, list);
    return list.toArray(new EvalNode[list.size()]);
  }

  private static void toConjunctiveNormalFormArrayRecursive(EvalNode node, List<EvalNode> found) {
    if (node.getType() == EvalType.AND) {
      toConjunctiveNormalFormArrayRecursive(node.getLeftExpr(), found);
      toConjunctiveNormalFormArrayRecursive(node.getRightExpr(), found);
    } else {
      found.add(node);
    }
  }

  /**
   * Convert a list of conjunctive normal forms into a singleton expression.
   *
   * @param cnfExprs
   * @return The EvalNode object that merges all CNF-formed expressions.
   */
  public static EvalNode createSingletonExprFromDNF(EvalNode... cnfExprs) {
    if (cnfExprs.length == 1) {
      return cnfExprs[0];
    }

    return createSingletonExprFromDNFRecursive(cnfExprs, 0);
  }

  private static EvalNode createSingletonExprFromDNFRecursive(EvalNode[] evalNode, int idx) {
    if (idx == evalNode.length - 2) {
      return new BinaryEval(EvalType.OR, evalNode[idx], evalNode[idx + 1]);
    } else {
      return new BinaryEval(EvalType.OR, evalNode[idx], createSingletonExprFromDNFRecursive(evalNode, idx + 1));
    }
  }

  /**
   * Transforms a expression to an array of disjunctive normal formed expressions.
   *
   * @param exprs The expressions to be transformed to an array of CNF-formed expressions.
   * @return An array of CNF-formed expressions
   */
  public static EvalNode [] toDisjunctiveNormalFormArray(EvalNode...exprs) {
    List<EvalNode> list = new ArrayList<EvalNode>();
    for (EvalNode expr : exprs) {
      toDisjunctiveNormalFormArrayRecursive(expr, list);
    }
    return list.toArray(new EvalNode[list.size()]);
  }

  private static void toDisjunctiveNormalFormArrayRecursive(EvalNode node, List<EvalNode> found) {
    if (node.getType() == EvalType.OR) {
      toDisjunctiveNormalFormArrayRecursive(node.getLeftExpr(), found);
      toDisjunctiveNormalFormArrayRecursive(node.getRightExpr(), found);
    } else {
      found.add(node);
    }
  }
}
