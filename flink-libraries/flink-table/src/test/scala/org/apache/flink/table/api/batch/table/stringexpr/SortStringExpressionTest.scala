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

package org.apache.flink.table.api.batch.table.stringexpr

import org.apache.flink.api.scala._
import org.apache.flink.table.api.scala._
import org.apache.flink.table.util.TableTestBase
import org.junit.Test

class SortStringExpressionTest extends TableTestBase {

  @Test
  def testOrdering(): Unit = {
    val util = batchTestUtil()
    val t = util.addTable[(Int, Long, String)]("Table3", 'a, 'b, 'c)

    val t1 = t.select('a, 'b, 'c).orderBy('a)
    val t2 = t.select("a, b, c").orderBy("a")

    verifyTableEquals(t1, t2)
  }

  @Test
  def testExplicitAscendOrdering(): Unit = {
    val util = batchTestUtil()
    val t = util.addTable[(Int, Long, String)]("Table3", 'a, 'b, 'c)

    val t1 = t.select('a, 'b).orderBy('a.asc)
    val t2 = t.select("a, b").orderBy("a.asc")

    verifyTableEquals(t1, t2)
  }

  @Test
  def testExplicitDescendOrdering(): Unit = {
    val util = batchTestUtil()
    val t = util.addTable[(Int, Long, String)]("Table3", 'a, 'b, 'c)

    val t1 = t.select('a, 'b).orderBy('a.desc)
    val t2 = t.select("a, b").orderBy("a.desc")

    verifyTableEquals(t1, t2)
  }
}
