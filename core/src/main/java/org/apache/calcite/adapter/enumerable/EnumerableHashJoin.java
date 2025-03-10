/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.adapter.enumerable;

import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.InvalidRelException;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelNodes;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.EquiJoin;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.metadata.RelMdCollation;
import org.apache.calcite.rel.metadata.RelMdUtil;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;

import java.util.Set;

/** Implementation of {@link org.apache.calcite.rel.core.Join} in
 * {@link org.apache.calcite.adapter.enumerable.EnumerableConvention enumerable calling convention}. */
public class EnumerableHashJoin extends EquiJoin implements EnumerableRel {
  /** Creates an EnumerableHashJoin.
   *
   * <p>Use {@link #create} unless you know what you're doing. */
  protected EnumerableHashJoin(
      RelOptCluster cluster,
      RelTraitSet traits,
      RelNode left,
      RelNode right,
      RexNode condition,
      Set<CorrelationId> variablesSet,
      JoinRelType joinType)
      throws InvalidRelException {
    super(
        cluster,
        traits,
        left,
        right,
        condition,
        variablesSet,
        joinType);
  }

  @Deprecated // to be removed before 2.0
  protected EnumerableHashJoin(RelOptCluster cluster, RelTraitSet traits,
      RelNode left, RelNode right, RexNode condition, ImmutableIntList leftKeys,
      ImmutableIntList rightKeys, JoinRelType joinType,
      Set<String> variablesStopped) throws InvalidRelException {
    this(cluster, traits, left, right, condition, CorrelationId.setOf(variablesStopped), joinType);
  }

  /** Creates an EnumerableHashJoin. */
  public static EnumerableHashJoin create(
      RelNode left,
      RelNode right,
      RexNode condition,
      Set<CorrelationId> variablesSet,
      JoinRelType joinType)
      throws InvalidRelException {
    final RelOptCluster cluster = left.getCluster();
    final RelMetadataQuery mq = cluster.getMetadataQuery();
    final RelTraitSet traitSet =
        cluster.traitSetOf(EnumerableConvention.INSTANCE)
            .replaceIfs(RelCollationTraitDef.INSTANCE,
                () -> RelMdCollation.enumerableHashJoin(mq, left, right, joinType));
    return new EnumerableHashJoin(cluster, traitSet, left, right, condition,
        variablesSet, joinType);
  }

  @Override public EnumerableHashJoin copy(RelTraitSet traitSet, RexNode condition,
      RelNode left, RelNode right, JoinRelType joinType,
      boolean semiJoinDone) {
    try {
      return new EnumerableHashJoin(getCluster(), traitSet, left, right,
          condition, variablesSet, joinType);
    } catch (InvalidRelException e) {
      // Semantic error not possible. Must be a bug. Convert to
      // internal error.
      throw new AssertionError(e);
    }
  }

  @Override public RelOptCost computeSelfCost(RelOptPlanner planner,
      RelMetadataQuery mq) {
    double rowCount = mq.getRowCount(this);

    if (!isSemiJoin()) {
      // Joins can be flipped, and for many algorithms, both versions are viable
      // and have the same cost. To make the results stable between versions of
      // the planner, make one of the versions slightly more expensive.
      switch (joinType) {
      case RIGHT:
        rowCount = RelMdUtil.addEpsilon(rowCount);
        break;
      default:
        if (RelNodes.COMPARATOR.compare(left, right) > 0) {
          rowCount = RelMdUtil.addEpsilon(rowCount);
        }
      }
    }

    // Cheaper if the smaller number of rows is coming from the LHS.
    // Model this by adding L log L to the cost.
    final double rightRowCount = right.estimateRowCount(mq);
    final double leftRowCount = left.estimateRowCount(mq);
    if (Double.isInfinite(leftRowCount)) {
      rowCount = leftRowCount;
    } else {
      rowCount += Util.nLogN(leftRowCount);
    }
    if (Double.isInfinite(rightRowCount)) {
      rowCount = rightRowCount;
    } else {
      rowCount += rightRowCount;
    }
    if (isSemiJoin()) {
      return planner.getCostFactory().makeCost(rowCount, 0, 0).multiplyBy(.01d);
    } else {
      return planner.getCostFactory().makeCost(rowCount, 0, 0);
    }
  }

  @Override public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
    if (isSemiJoin()) {
      assert joinInfo.isEqui();
      return implementHashSemiJoin(implementor, pref);
    }
    return implementHashJoin(implementor, pref);
  }

  private Result implementHashSemiJoin(EnumerableRelImplementor implementor, Prefer pref) {
    BlockBuilder builder = new BlockBuilder();
    final Result leftResult =
        implementor.visitChild(this, 0, (EnumerableRel) left, pref);
    Expression leftExpression =
        builder.append(
            "left", leftResult.block);
    final Result rightResult =
        implementor.visitChild(this, 1, (EnumerableRel) right, pref);
    Expression rightExpression =
        builder.append(
            "right", rightResult.block);
    final PhysType physType = leftResult.physType;
    return implementor.result(
        physType,
        builder.append(
            Expressions.call(
                BuiltInMethod.SEMI_JOIN.method,
                Expressions.list(
                    leftExpression,
                    rightExpression,
                    leftResult.physType.generateAccessor(joinInfo.leftKeys),
                    rightResult.physType.generateAccessor(joinInfo.rightKeys))))
            .toBlock());
  }

  private Result implementHashJoin(EnumerableRelImplementor implementor, Prefer pref) {
    BlockBuilder builder = new BlockBuilder();
    final Result leftResult =
        implementor.visitChild(this, 0, (EnumerableRel) left, pref);
    Expression leftExpression =
        builder.append(
            "left", leftResult.block);
    final Result rightResult =
        implementor.visitChild(this, 1, (EnumerableRel) right, pref);
    Expression rightExpression =
        builder.append(
            "right", rightResult.block);
    final PhysType physType =
        PhysTypeImpl.of(
            implementor.getTypeFactory(), getRowType(), pref.preferArray());
    final PhysType keyPhysType =
        leftResult.physType.project(
            joinInfo.leftKeys, JavaRowFormat.LIST);
    return implementor.result(
        physType,
        builder.append(
            Expressions.call(
                leftExpression,
                BuiltInMethod.HASH_JOIN.method,
                Expressions.list(
                    rightExpression,
                    leftResult.physType.generateAccessor(joinInfo.leftKeys),
                    rightResult.physType.generateAccessor(joinInfo.rightKeys),
                    EnumUtils.joinSelector(joinType,
                        physType,
                        ImmutableList.of(
                            leftResult.physType, rightResult.physType)))
                    .append(
                        Util.first(keyPhysType.comparer(),
                            Expressions.constant(null)))
                    .append(
                        Expressions.constant(joinType.generatesNullsOnLeft()))
                    .append(
                        Expressions.constant(
                            joinType.generatesNullsOnRight())))).toBlock());
  }
}

// End EnumerableHashJoin.java
