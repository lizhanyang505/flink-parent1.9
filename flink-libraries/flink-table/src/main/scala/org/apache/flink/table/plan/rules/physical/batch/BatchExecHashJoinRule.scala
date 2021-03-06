/*
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

package org.apache.flink.table.plan.rules.physical.batch

import org.apache.flink.table.api.{OperatorType, TableConfig, TableConfigOptions}
import org.apache.flink.table.plan.FlinkJoinRelType
import org.apache.flink.table.plan.`trait`.FlinkRelDistribution
import org.apache.flink.table.plan.nodes.FlinkConventions
import org.apache.flink.table.plan.nodes.logical.{FlinkLogicalJoin, FlinkLogicalSemiJoin}
import org.apache.flink.table.plan.nodes.physical.batch.{BatchExecHashJoin, BatchExecHashSemiJoin}

import org.apache.calcite.plan.RelOptRule.{any, operand}
import org.apache.calcite.plan.{RelOptRule, RelOptRuleCall, RelTraitSet}
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.{Join, SemiJoin}
import org.apache.calcite.util.ImmutableIntList

import java.lang.Double
import java.util

import scala.collection.JavaConversions._

class BatchExecHashJoinRule(joinClass: Class[_ <: Join])
    extends RelOptRule(
      operand(
        joinClass,
        operand(classOf[RelNode], any)), s"BatchExecHashJoinRule_${joinClass.getSimpleName}")
  with BatchExecJoinRuleBase {

  override def matches(call: RelOptRuleCall): Boolean = {
    val join: Join = call.rel(0)
    val joinInfo = join.analyzeCondition
    val tableConfig = call.getPlanner.getContext.unwrap(classOf[TableConfig])
    val enableShuffleHash = tableConfig.enabledGivenOpType(OperatorType.ShuffleHashJoin)
    val enableBroadcastHash = tableConfig.enabledGivenOpType(OperatorType.BroadcastHashJoin)

    val (isBroadcast, _) = canBroadcast(
      join,
      binaryRowRelNodeSize(join.getLeft),
      binaryRowRelNodeSize(join.getRight))

    !joinInfo.pairs().isEmpty && (if (isBroadcast) enableBroadcastHash else enableShuffleHash)
  }

  override def onMatch(call: RelOptRuleCall): Unit = {
    val join: Join = call.rel(0)
    val joinInfo = join.analyzeCondition

    val left = join.getLeft
    val (right, tryDistinctBuildRow) = {
      val right = join.getRight
      join match {
        case _: SemiJoin =>
          // We can do a distinct to buildSide(right) when semi join.
          val distinctKeys = 0 until right.getRowType.getFieldCount
          val useBuildDistinct = chooseSemiBuildDistinct(right, distinctKeys)
          if (useBuildDistinct) {
            (addLocalDistinctAgg(right, distinctKeys, call.builder()), true)
          } else {
            (right, false)
          }
        case _ => (join.getRight, false)
      }
    }

    val leftSize = binaryRowRelNodeSize(left)
    val rightSize = binaryRowRelNodeSize(right)

    val (isBroadcast, leftIsBroadcast) = canBroadcast(join, leftSize, rightSize)

    val leftIsBuild = if (isBroadcast) {
      leftIsBroadcast
    } else if (leftSize == null || rightSize == null || leftSize == rightSize) {
      // use left to build hash table if leftSize or rightSize is unknown or equal size.
      // choose right to build if join is semiJoin.
      !join.isInstanceOf[SemiJoin]
    } else {
      leftSize < rightSize
    }


    def transformToEquiv(leftRequiredTrait: RelTraitSet, rightRequiredTrait: RelTraitSet): Unit = {
      val newLeft = RelOptRule.convert(left, leftRequiredTrait)
      val newRight = RelOptRule.convert(right, rightRequiredTrait)
      val providedTraitSet = join.getTraitSet.replace(FlinkConventions.BATCH_PHYSICAL)

      call.transformTo(join match {
        case sj: SemiJoin =>
          new BatchExecHashSemiJoin(
            sj.getCluster,
            providedTraitSet,
            newLeft,
            newRight,
            leftIsBuild,
            join.getCondition,
            sj.leftKeys,
            sj.rightKeys,
            sj.isAnti,
            isBroadcast,
            tryDistinctBuildRow,
            description)
        case _ =>
          new BatchExecHashJoin(
            join.getCluster,
            providedTraitSet,
            newLeft,
            newRight,
            leftIsBuild,
            join.getCondition,
            join.getJoinType,
            isBroadcast,
            description)
      })
    }

    if (isBroadcast) {
      val probeTrait = join.getTraitSet.replace(FlinkConventions.BATCH_PHYSICAL)
      val buildTrait = join.getTraitSet.replace(FlinkConventions.BATCH_PHYSICAL)
          .replace(FlinkRelDistribution.BROADCAST_DISTRIBUTED)
      if (leftIsBroadcast) {
        transformToEquiv(buildTrait, probeTrait)
      } else {
        transformToEquiv(probeTrait, buildTrait)
      }
    } else {
      val toHashTraitByColumns = (columns: util.Collection[_ <: Number]) =>
        join.getCluster.getPlanner.emptyTraitSet.
            replace(FlinkConventions.BATCH_PHYSICAL).
            replace(FlinkRelDistribution.hash(columns))
      transformToEquiv(
        toHashTraitByColumns(joinInfo.leftKeys),
        toHashTraitByColumns(joinInfo.rightKeys))
      // add more possibility to only shuffle by partial joinKeys, now only single one
      val tableConfig = call.getPlanner.getContext.unwrap(classOf[TableConfig])
      val isShuffleByPartialKeyEnabled = tableConfig.getConf.getBoolean(
        TableConfigOptions.SQL_OPTIMIZER_SHUFFLE_PARTIAL_KEY_ENABLED)
      if (isShuffleByPartialKeyEnabled && joinInfo.pairs().length > 1) {
        joinInfo.pairs().foreach { pair =>
          transformToEquiv(
            toHashTraitByColumns(ImmutableIntList.of(pair.source)),
            toHashTraitByColumns(ImmutableIntList.of(pair.target)))
        }
      }
    }

  }

  /**
    * Decides whether the join can convert to BroadcastHashJoin.
    *
    * @param join      the original join node to convert
    * @param leftSize  size of join left child
    * @param rightSize size of join right child
    * @return an Tuple2 instance. The first element of tuple is true if join can convert to
    *         broadcast hash join, false else. The second element of tuple is true if left side used
    *         as broadcast side, false else.
    */
  private def canBroadcast(join: Join, leftSize: Double, rightSize: Double): (Boolean, Boolean) = {
    // if leftSize or rightSize is unknown, cannot use broadcast
    if (leftSize == null || rightSize == null) {
      return (false, false)
    }
    val conf = join.getCluster.getPlanner.getContext.unwrap(classOf[TableConfig])
    val threshold = conf.getConf.getLong(TableConfigOptions.SQL_EXEC_HASH_JOIN_BROADCAST_THRESHOLD)
    val joinType = getFlinkJoinRelType(join)
    joinType match {
      case FlinkJoinRelType.LEFT => (rightSize <= threshold, false)
      case FlinkJoinRelType.RIGHT => (leftSize <= threshold, true)
      case FlinkJoinRelType.FULL => (false, false)
      case FlinkJoinRelType.INNER =>
        (leftSize <= threshold || rightSize <= threshold, leftSize < rightSize)
      // left side cannot be used as build side in SEMI/ANTI join.
      case FlinkJoinRelType.SEMI | FlinkJoinRelType.ANTI => (rightSize <= threshold, false)
    }
  }
}

object BatchExecHashJoinRule {
  val INSTANCE = new BatchExecHashJoinRule(classOf[FlinkLogicalJoin])
  val SEMI_JOIN = new BatchExecHashJoinRule(classOf[FlinkLogicalSemiJoin])
}
