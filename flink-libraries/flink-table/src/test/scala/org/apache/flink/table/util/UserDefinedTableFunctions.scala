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
package org.apache.flink.table.util

import java.lang.Boolean
import java.sql.Timestamp

import org.apache.flink.api.java.tuple.Tuple3
import org.apache.flink.table.api.ValidationException
import org.apache.flink.table.api.functions.{FunctionContext, ScalarFunction, TableFunction}
import org.apache.flink.table.api.types.{DataType, DataTypes}
import org.apache.flink.types.Row
import org.junit.Assert

import scala.annotation.varargs


case class SimpleUser(name: String, age: Int)

class TableFunc0 extends TableFunction[SimpleUser] {
  // make sure input element's format is "<string>#<int>"
  def eval(user: String): Unit = {
    if (user.contains("#")) {
      val splits = user.split("#")
      collect(SimpleUser(splits(0), splits(1).toInt))
    }
  }
}

class TableFunc1 extends TableFunction[String] {
  def eval(str: String): Unit = {
    if (str.contains("#")){
      str.split("#").foreach(collect)
    }
  }

  def eval(str: String, prefix: String): Unit = {
    if (str.contains("#")) {
      str.split("#").foreach(s => collect(prefix + s))
    }
  }
}

class TableFunc2 extends TableFunction[Row] {
  def eval(str: String): Unit = {
    if (str.contains("#")) {
      str.split("#").foreach({ s =>
        val row = new Row(2)
        row.setField(0, s)
        row.setField(1, s.length)
        collect(row)
      })
    }
  }

  override def getResultType(arguments: Array[AnyRef], argTypes: Array[Class[_]]): DataType =
    DataTypes.createRowType(DataTypes.STRING, DataTypes.INT)
}

class TableFunc3(data: String, conf: Map[String, String]) extends TableFunction[SimpleUser] {

  def this(data: String) {
    this(data, null)
  }

  def eval(user: String): Unit = {
    if (user.contains("#")) {
      val splits = user.split("#")
      if (null != data) {
        if (null != conf && conf.size > 0) {
          val it = conf.keys.iterator
          while (it.hasNext) {
            val key = it.next()
            val value = conf.get(key).get
            collect(
              SimpleUser(
                data.concat("_key=")
                .concat(key)
                .concat("_value=")
                .concat(value)
                .concat("_")
                .concat(splits(0)),
                splits(1).toInt))
          }
        } else {
          collect(SimpleUser(data.concat(splits(0)), splits(1).toInt))
        }
      } else {
        collect(SimpleUser(splits(0), splits(1).toInt))
      }
    }
  }
}

class TableFunc5 extends TableFunction[Tuple2[String, Int]] {
  def eval(bytes: Array[Byte]) {
    if (null != bytes) {
      collect(new Tuple2(new String(bytes), bytes.length))
    }
  }

  def eval(str: String) {
    if (null != str) {
      collect(new Tuple2(str, str.length))
    }
  }
}

class UDTFWithDynamicType extends TableFunction[Row] {

  def eval(str: String, column: Int): Unit = {
    if (str.contains("#")) {
      str.split("#").foreach({ s =>
        val row = new Row(column)
        row.setField(0, s)
        var i = 0
        for (i <- 1 until column) {
          row.setField(i, s.length)
        }
        collect(row)
      })
    }
  }

  override def getResultType(
      arguments: Array[AnyRef],
      typeInfos: Array[Class[_]]): DataType = {
    assert(typeInfos(1).isPrimitive)
    assert(typeInfos(1).equals(325.getClass))
    val column = arguments(1).asInstanceOf[Int]
    val basicTypeInfos = Array.fill[DataType](column)(DataTypes.INT)
    basicTypeInfos(0) = DataTypes.STRING
    DataTypes.createRowType(basicTypeInfos: _*)
  }
}

class UDTFWithDynamicType0 extends TableFunction[Row] {

  def eval(str: String, cols: String): Unit = {
    val columns = cols.split(",")

    if (str.contains("#")) {
      str.split("#").foreach({ s =>
        val row = new Row(columns.length)
        row.setField(0, s)
        for (i <- 1 until columns.length) {
          if (columns(i).equals("string")) {
            row.setField(i, s.length.toString)
          } else if (columns(i).equals("int")) {
            row.setField(i, s.length)
          }
        }
        collect(row)
      })
    }
  }

  override def getResultType(
      arguments: Array[AnyRef],
      typeInfos: Array[Class[_]]): DataType = {
    assert(typeInfos(1).equals(Class.forName("java.lang.String")))
    val columnStr = arguments(1).asInstanceOf[String]
    val columns = columnStr.split(",")

    val basicTypeInfos = for (c <- columns) yield c match {
      case "string" => DataTypes.STRING
      case "int" => DataTypes.INT
    }
    DataTypes.createRowType(basicTypeInfos: _*)
  }
}

class UDTFWithDynamicType1 extends TableFunction[Row] {

  def eval(col: String): Unit = {
    val row = new Row(1)
    col match {
      case "string" => row.setField(0, "string")
      case "int" => row.setField(0, 4)
      case "double" => row.setField(0, 3.25)
      case "boolean" => row.setField(0, true)
      case "timestamp" => row.setField(0, new Timestamp(325L))
    }
    collect(row)
  }

  override def getResultType(
      arguments: Array[AnyRef],
      typeInfos: Array[Class[_]]): DataType = {
    assert(typeInfos(0).equals(Class.forName("java.lang.String")))
    val columnStr = arguments(0).asInstanceOf[String]
    columnStr match {
      case "string" => DataTypes.createRowType(DataTypes.STRING)
      case "int" => DataTypes.createRowType(DataTypes.INT)
      case "double" => DataTypes.createRowType(DataTypes.DOUBLE)
      case "boolean" => DataTypes.createRowType(DataTypes.BOOLEAN)
      case "timestamp" => DataTypes.createRowType(DataTypes.TIMESTAMP)
      case _ => DataTypes.createRowType(DataTypes.INT)
    }
  }
}

class UDTFWithDynamicTypeAndRexNodes extends TableFunction[Row] {

  def eval(str: String, i: Int, si: Int, bi: Int, flt: Double, real: Double, d: Double,
      b: Boolean, ts: Timestamp):
  Unit = {
    val row = new Row(9)
    row.setField(0, str)
    row.setField(1, i)
    row.setField(2, si)
    row.setField(3, bi)
    row.setField(4, flt)
    row.setField(5, real)
    row.setField(6, d)
    row.setField(7, b)
    row.setField(8, ts)
    collect(row)
  }

  override def getResultType(
      arguments: Array[AnyRef],
      typeInfos: Array[Class[_]]): DataType = {
    // Test for the transformRexNodes()
    // No assertion here, argument 0 is not literal
    val str = arguments(0).asInstanceOf[String]
    if (null != str) {
      throw new RuntimeException("The first column should be null")
    }

    assert(typeInfos(1).isPrimitive)
    assert(typeInfos(1).equals(325.getClass))
    val i = arguments(1).asInstanceOf[Int]
    if (i <= 0) {
      throw new RuntimeException("The arguments should be greater than zero")
    }

    assert(typeInfos(2).isPrimitive)
    assert(typeInfos(2).equals(325.getClass))
    val si = arguments(2).asInstanceOf[Int]
    if (si <= 0) {
      throw new RuntimeException("The arguments should be greater than zero")
    }

    assert(typeInfos(3).isPrimitive)
    assert(typeInfos(3).equals(325.getClass))
    val bi = arguments(3).asInstanceOf[Int]
    if (bi <= 0) {
      throw new RuntimeException("The arguments should be greater than zero")
    }

    assert(typeInfos(4).isPrimitive)
    assert(typeInfos(4).equals(3.25.getClass))
    val float = arguments(4).asInstanceOf[Double]
    if (float <= 0) {
      throw new RuntimeException("The arguments should be greater than zero")
    }

    assert(typeInfos(5).isPrimitive)
    assert(typeInfos(5).equals(3.25.getClass))
    val real = arguments(5).asInstanceOf[Double]
    if (real <= 0) {
      throw new RuntimeException("The arguments should be greater than zero")
    }

    assert(typeInfos(6).isPrimitive)
    assert(typeInfos(6).equals(3.25.getClass))
    val d = arguments(6).asInstanceOf[Double]
    if (d <= 0) {
      throw new RuntimeException("The arguments should be greater than zero")
    }

    assert(typeInfos(7).equals(Class.forName("java.lang.Boolean")))
    val b = arguments(7).asInstanceOf[Boolean]
    if (!b) {
      throw new RuntimeException("The arguments should be true")
    }

    assert(typeInfos(8).equals(Class.forName("java.sql.Timestamp")))
    val ts = arguments(8).asInstanceOf[Timestamp]
    if (ts.getTime <= 0) {
      throw new RuntimeException("The arguments should be greater than zero")
    }

    DataTypes.createRowType(
      DataTypes.STRING,
      DataTypes.INT,
      DataTypes.INT,
      DataTypes.INT,
      DataTypes.DOUBLE,
      DataTypes.DOUBLE,
      DataTypes.DOUBLE,
      DataTypes.BOOLEAN,
      DataTypes.TIMESTAMP
    )
  }
}

class UDTFWithDynamicTypeAndVariableArgs extends TableFunction[Row] {

  def eval(value: Int): Unit = {
    val v = new Integer(value)
    collect(Row.of(v, v))
    collect(Row.of(v, v))
  }

  @varargs
  def eval(str: String, cols: String, fields: AnyRef*): Unit = {
    val columns = cols.split(",")

    if (str.contains("#")) {
      str.split("#").foreach({ s =>
        val row = new Row(columns.length)
        row.setField(0, s)
        for (i <- 1 until columns.length) {
          if (columns(i).equals("string")) {
            row.setField(i, s.length.toString)
          } else if (columns(i).equals("int")) {
            row.setField(i, s.length)
          }
        }
        collect(row)
      })
    }
  }

  override def getResultType(
      arguments: Array[AnyRef],
      typeInfos: Array[Class[_]]): DataType = {
    if (typeInfos.length == 1) {
      DataTypes.createRowType(DataTypes.INT, DataTypes.INT)
    } else {
      assert(typeInfos(1).equals(Class.forName("java.lang.String")))
      val columnStr = arguments(1).asInstanceOf[String]
      val columns = columnStr.split(",")

      val basicTypeInfos = for (c <- columns) yield c match {
        case "string" => DataTypes.STRING
        case "int" => DataTypes.INT
      }
      DataTypes.createRowType(basicTypeInfos: _*)
    }
  }
}

class TableFunc4 extends TableFunction[Row] {
  def eval(b: Byte, s: Short, f: Float): Unit = {
    collect(Row.of("Byte=" + b, "Short=" + s, "Float=" + f))
  }

  override def getResultType(
      arguments: Array[AnyRef],
      typeInfos: Array[Class[_]]): DataType = {
    DataTypes.createRowType(DataTypes.STRING, DataTypes.STRING, DataTypes.STRING)
  }
}

class TableFunc6 extends TableFunction[Row] {
  def eval(row: Row): Unit = {
    collect(row)
  }

  override def getParameterTypes(signature: Array[Class[_]]): Array[DataType] =
    Array(DataTypes.createRowType(DataTypes.INT, DataTypes.INT, DataTypes.INT))

  override def getResultType(
      arguments: Array[AnyRef],
      typeInfos: Array[Class[_]]): DataType = {
    DataTypes.createRowType(DataTypes.INT, DataTypes.INT, DataTypes.INT)
  }
}

class TableFunc7 extends TableFunction[Row] {

  def eval(row: Row): Unit = {
  }

  def eval(row: java.util.List[Row]): Unit = {
  }
}

class RF extends ScalarFunction {

  def eval(x: Int): java.util.List[Row] = {
    java.util.Collections.emptyList()
  }
}

class VarArgsFunc0 extends TableFunction[String] {
  @varargs
  def eval(str: String*): Unit = {
    str.foreach(collect)
  }
}

class HierarchyTableFunction extends SplittableTableFunction[Boolean, Integer] {
  def eval(user: String) {
    if (user.contains("#")) {
      val splits = user.split("#")
      val age = splits(1).toInt
      collect(new Tuple3[String, Boolean, Integer](splits(0), age >= 20, age))
    }
  }
}

abstract class SplittableTableFunction[A, B] extends TableFunction[Tuple3[String, A, B]] {}

class PojoTableFunc extends TableFunction[PojoUser] {
  def eval(user: String) {
    if (user.contains("#")) {
      val splits = user.split("#")
      collect(new PojoUser(splits(0), splits(1).toInt))
    }
  }
}

class PojoUser() {
  var name: String = _
  var age: Int = 0

  def this(name: String, age: Int) {
    this()
    this.name = name
    this.age = age
  }
}

// ----------------------------------------------------------------------------------------------
// Invalid Table Functions
// ----------------------------------------------------------------------------------------------


// this is used to check whether scala object is forbidden
object ObjectTableFunction extends TableFunction[Integer] {
  def eval(a: Int, b: Int): Unit = {
    collect(a)
    collect(b)
  }
}

class RichTableFunc0 extends TableFunction[String] {
  var openCalled = false
  var closeCalled = false

  override def open(context: FunctionContext): Unit = {
    super.open(context)
    if (closeCalled) {
      Assert.fail("Close called before open.")
    }
    openCalled = true
  }

  def eval(str: String): Unit = {
    if (!openCalled) {
      Assert.fail("Open was not called before eval.")
    }
    if (closeCalled) {
      Assert.fail("Close called before eval.")
    }

    if (!str.contains("#")) {
      collect(str)
    }
  }

  override def close(): Unit = {
    super.close()
    if (!openCalled) {
      Assert.fail("Open was not called before close.")
    }
    closeCalled = true
  }
}

class RichTableFunc1 extends TableFunction[String] {
  var separator: Option[String] = None

  override def open(context: FunctionContext): Unit = {
    separator = Some(context.getJobParameter("word_separator", ""))
  }

  def eval(str: String): Unit = {
    if (str.contains(separator.getOrElse(throw new ValidationException(s"no separator")))) {
      str.split(separator.get).foreach(collect)
    }
  }

  override def close(): Unit = {
    separator = None
  }
}
