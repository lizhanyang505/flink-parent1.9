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

package org.apache.flink.table.codegen.calls

import org.apache.calcite.avatica.util.DateTimeUtils.MILLIS_PER_DAY
import org.apache.calcite.avatica.util.TimeUnit
import org.apache.calcite.util.BuiltInMethod
import org.apache.flink.table.api.types.{DataTypes, InternalType}
import org.apache.flink.table.codegen.CodeGenUtils._
import org.apache.flink.table.codegen.calls.CallGenerator.generateCallIfArgsNotNull
import org.apache.flink.table.codegen.{CodeGenException, CodeGeneratorContext, GeneratedExpression}

class TimestampDiffCallGen extends CallGenerator {

  override def generate(
      ctx: CodeGeneratorContext,
      operands: Seq[GeneratedExpression],
      returnType: InternalType,
      nullCheck: Boolean)
  : GeneratedExpression = {

    val unit = getEnum(operands.head).asInstanceOf[TimeUnit]
    unit match {
      case TimeUnit.YEAR |
           TimeUnit.MONTH |
           TimeUnit.QUARTER =>
        (operands(1).resultType, operands(2).resultType) match {
          case (DataTypes.TIMESTAMP, DataTypes.DATE) =>
            generateCallIfArgsNotNull(ctx, nullCheck, DataTypes.INT, operands) {
              (terms) =>
                s"""
                   |${qualifyMethod(BuiltInMethod.SUBTRACT_MONTHS.method)}(${terms(1)},
                   |  ${terms(2)} * ${MILLIS_PER_DAY}L) / ${unit.multiplier.intValue()}
                   |""".stripMargin
            }

          case (DataTypes.DATE, DataTypes.TIMESTAMP) =>
            generateCallIfArgsNotNull(ctx, nullCheck, DataTypes.INT, operands) {
              (terms) =>
                s"""
                   |${qualifyMethod(BuiltInMethod.SUBTRACT_MONTHS.method)}(
                   |${terms(1)} * ${MILLIS_PER_DAY}L, ${terms(2)}) / ${unit.multiplier.intValue()}
                   |""".stripMargin
            }

          case _ =>
            generateCallIfArgsNotNull(ctx, nullCheck, DataTypes.INT, operands) {
              (terms) =>
                s"""
                   |${qualifyMethod(BuiltInMethod.SUBTRACT_MONTHS.method)}(${terms(1)},
                   |  ${terms(2)}) / ${unit.multiplier.intValue()}
                   |""".stripMargin
            }
        }

      case TimeUnit.WEEK |
           TimeUnit.DAY |
           TimeUnit.HOUR |
           TimeUnit.MINUTE |
           TimeUnit.SECOND =>
        (operands(1).resultType, operands(2).resultType) match {
          case (DataTypes.TIMESTAMP, DataTypes.TIMESTAMP) =>
            generateCallIfArgsNotNull(ctx, nullCheck, DataTypes.INT, operands) {
              (terms) =>
                s"""
                   |(int)((${terms(1)} - ${terms(2)}) / ${unit.multiplier.intValue()})
                   |""".stripMargin
            }

          case (DataTypes.TIMESTAMP, DataTypes.DATE) =>
            generateCallIfArgsNotNull(ctx, nullCheck, DataTypes.INT, operands) {
              (terms) =>
                s"""
                   |(int)((${terms(1)} -
                   |  ${terms(2)} * ${MILLIS_PER_DAY}L) / ${unit.multiplier.intValue()})
                   |""".stripMargin
            }

          case (DataTypes.DATE, DataTypes.TIMESTAMP) =>
            generateCallIfArgsNotNull(ctx, nullCheck, DataTypes.INT, operands) {
              (terms) =>
                s"""
                   |(int)((${terms(1)} * ${MILLIS_PER_DAY}L -
                   |  ${terms(2)}) / ${unit.multiplier.intValue()})
                   |""".stripMargin
            }

          case (DataTypes.DATE, DataTypes.DATE) =>
            generateCallIfArgsNotNull(ctx, nullCheck, DataTypes.INT, operands) {
              (terms) =>
                s"""
                   |(int)((${terms(1)} * ${MILLIS_PER_DAY}L -
                   |  ${terms(2)} * ${MILLIS_PER_DAY}L) / ${unit.multiplier.intValue()})
                   |""".stripMargin
            }
        }

      case _ =>
        throw new CodeGenException(
          "Unit '" + unit + "' can not be applied to the timestamp difference function.")
    }
  }
}
