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
package org.apache.flink.table.runtime.batch.sql.agg

import org.apache.flink.table.runtime.batch.sql.BatchTestBase
import org.apache.flink.table.runtime.batch.sql.BatchTestBase.row
import org.apache.flink.table.runtime.utils.CommonTestData

import org.junit.{Before, Test}

import scala.collection.Seq

class PruneAggregateCallRuleITCase extends BatchTestBase {

  @Before
  def before(): Unit = {
    tEnv.registerTableSource("MyTable",
      CommonTestData.getSmall3Source(Array("a", "b", "c")))
    tEnv.registerTableSource("MyTable2",
      CommonTestData.getSmall5Source(Array("a", "b", "c", "d", "e"), Set(Set("b"))))
  }

  @Test
  def testNoneEmptyGroupKey(): Unit = {
    checkResult(
      "SELECT a FROM (SELECT b, MAX(a) AS a, COUNT(*), MAX(c) FROM MyTable GROUP BY b) t",
      Seq(row(1), row(3))
    )
    checkResult(
      """
        |SELECT c, a FROM
        | (SELECT a, c, COUNT(b) as c, SUM(b) as s FROM MyTable GROUP BY a, c) t
        |WHERE s > 1
      """.stripMargin,
      Seq(row("Hello world", 3), row("Hello", 2))
    )
    checkResult(
      "SELECT a, c FROM (SELECT a, b, SUM(c) as c, COUNT(d) as d FROM MyTable2 GROUP BY a, b) t",
      Seq(row(1, 0), row(2, 1), row(2, 2)))

    checkResult(
      "SELECT a FROM (SELECT a, b, SUM(c) as c, COUNT(d) as d FROM MyTable2 GROUP BY a, b) t",
      Seq(row(1), row(2), row(2)))
  }

  @Test
  def testEmptyGroupKey(): Unit = {
    checkResult(
      "SELECT 1 FROM (SELECT SUM(a) FROM MyTable) t",
      Seq(row(1))
    )

    checkResult(
      "SELECT * FROM MyTable WHERE EXISTS (SELECT COUNT(*) FROM MyTable2)",
      Seq(row(1, 1, "Hi"), row(2, 2, "Hello"), row(3, 2, "Hello world"))
    )

    checkResult(
      "SELECT * FROM MyTable WHERE EXISTS (SELECT COUNT(*) FROM MyTable2 WHERE 1=2)",
      Seq(row(1, 1, "Hi"), row(2, 2, "Hello"), row(3, 2, "Hello world"))
    )

    checkResult(
      "SELECT 1 FROM (SELECT SUM(a), COUNT(*) FROM MyTable) t",
      Seq(row(1))
    )

    checkResult(
      "SELECT 1 FROM (SELECT SUM(a), COUNT(*) FROM MyTable WHERE 1=2) t",
      Seq(row(1))
    )

    checkResult(
      "SELECT 1 FROM (SELECT COUNT(*), SUM(a) FROM MyTable) t",
      Seq(row(1))
    )

    checkResult(
      "SELECT 1 FROM (SELECT COUNT(*), SUM(a) FROM MyTable WHERE 1=2) t",
      Seq(row(1))
    )

    checkResult(
      "SELECT * FROM MyTable WHERE EXISTS (SELECT SUM(a), COUNT(*) FROM MyTable2)",
      Seq(row(1, 1, "Hi"), row(2, 2, "Hello"), row(3, 2, "Hello world"))
    )

    checkResult(
      "SELECT * FROM MyTable WHERE EXISTS (SELECT COUNT(*), SUM(a) FROM MyTable2)",
      Seq(row(1, 1, "Hi"), row(2, 2, "Hello"), row(3, 2, "Hello world"))
    )

    checkResult(
      "SELECT * FROM MyTable WHERE EXISTS (SELECT SUM(a), COUNT(*) FROM MyTable2 WHERE 1=2)",
      Seq(row(1, 1, "Hi"), row(2, 2, "Hello"), row(3, 2, "Hello world"))
    )

    checkResult(
      "SELECT * FROM MyTable WHERE EXISTS (SELECT COUNT(*), SUM(a) FROM MyTable2 WHERE 1=2)",
      Seq(row(1, 1, "Hi"), row(2, 2, "Hello"), row(3, 2, "Hello world"))
    )
  }

}
