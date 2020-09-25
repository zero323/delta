/*
 * Copyright (2020) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta

import java.io.File

import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.hadoop.fs.Path
import org.scalatest.BeforeAndAfterEach

import org.apache.spark.sql.{AnalysisException, DataFrame, QueryTest, Row}
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.util.Utils

abstract class DeleteSuiteBase extends QueryTest
  with SharedSparkSession with BeforeAndAfterEach {

  import testImplicits._

  var tempDir: File = _

  var deltaLog: DeltaLog = _

  protected def tempPath: String = tempDir.getCanonicalPath

  protected def readDeltaTable(path: String): DataFrame = {
    spark.read.format("delta").load(path)
  }

  override def beforeEach() {
    super.beforeEach()
    tempDir = Utils.createTempDir()
    deltaLog = DeltaLog.forTable(spark, new Path(tempPath))
  }

  override def afterEach() {
    try {
      Utils.deleteRecursively(tempDir)
      DeltaLog.clearCache()
    } finally {
      super.afterEach()
    }
  }

  protected def executeDelete(target: String, where: String = null): Unit

  protected def append(df: DataFrame, partitionBy: Seq[String] = Nil): Unit = {
    val writer = df.write.format("delta").mode("append")
    if (partitionBy.nonEmpty) {
      writer.partitionBy(partitionBy: _*)
    }
    writer.save(deltaLog.dataPath.toString)
  }

  protected def checkDelete(
      condition: Option[String],
      expectedResults: Seq[Row],
      tableName: Option[String] = None): Unit = {
    executeDelete(target = tableName.getOrElse(s"delta.`$tempPath`"), where = condition.orNull)
    checkAnswer(readDeltaTable(tempPath), expectedResults)
  }

  Seq(true, false).foreach { isPartitioned =>
    test(s"basic case - Partition=$isPartitioned") {
      val partitions = if (isPartitioned) "key" :: Nil else Nil
      append(Seq((2, 2), (1, 4), (1, 1), (0, 3)).toDF("key", "value"), partitions)

      checkDelete(condition = None, Nil)
    }
  }

  Seq(true, false).foreach { isPartitioned =>
    test(s"basic case - delete from a Delta table by path - Partition=$isPartitioned") {
      withTable("deltaTable") {
        val partitions = if (isPartitioned) "key" :: Nil else Nil
        val input = Seq((2, 2), (1, 4), (1, 1), (0, 3)).toDF("key", "value")
        append(input, partitions)

        checkDelete(Some("value = 4 and key = 3"),
          Row(2, 2) :: Row(1, 4) :: Row(1, 1) :: Row(0, 3) :: Nil)
        checkDelete(Some("value = 4 and key = 1"),
          Row(2, 2) :: Row(1, 1) :: Row(0, 3) :: Nil)
        checkDelete(Some("value = 2 or key = 1"),
          Row(0, 3) :: Nil)
        checkDelete(Some("key = 0 or value = 99"), Nil)
      }
    }
  }

  Seq(true, false).foreach { isPartitioned =>
    test(s"basic case - delete from a Delta table by name - Partition=$isPartitioned") {
      withTable("delta_table") {
        val partitionByClause = if (isPartitioned) "PARTITIONED BY (key)" else ""
        sql(
          s"""
             |CREATE TABLE delta_table(key INT, value INT)
             |USING delta
             |OPTIONS('path'='$tempPath')
             |$partitionByClause
           """.stripMargin)

        val input = Seq((2, 2), (1, 4), (1, 1), (0, 3)).toDF("key", "value")
        append(input)

        checkDelete(Some("value = 4 and key = 3"),
          Row(2, 2) :: Row(1, 4) :: Row(1, 1) :: Row(0, 3) :: Nil,
          Some("delta_table"))
        checkDelete(Some("value = 4 and key = 1"),
          Row(2, 2) :: Row(1, 1) :: Row(0, 3) :: Nil,
          Some("delta_table"))
        checkDelete(Some("value = 2 or key = 1"),
          Row(0, 3) :: Nil,
          Some("delta_table"))
        checkDelete(Some("key = 0 or value = 99"),
          Nil,
          Some("delta_table"))
      }
    }
  }

  Seq(true, false).foreach { isPartitioned =>
    test(s"basic key columns - Partition=$isPartitioned") {
      val input = Seq((2, 2), (1, 4), (1, 1), (0, 3)).toDF("key", "value")
      val partitions = if (isPartitioned) "key" :: Nil else Nil
      append(input, partitions)

      checkDelete(Some("key > 2"), Row(2, 2) :: Row(1, 4) :: Row(1, 1) :: Row(0, 3) :: Nil)
      checkDelete(Some("key < 2"), Row(2, 2) :: Nil)
      checkDelete(Some("key = 2"), Nil)
    }
  }

  Seq(true, false).foreach { isPartitioned =>
    test(s"where key columns - Partition=$isPartitioned") {
      val partitions = if (isPartitioned) "key" :: Nil else Nil
      append(Seq((2, 2), (1, 4), (1, 1), (0, 3)).toDF("key", "value"), partitions)

      checkDelete(Some("key = 1"), Row(2, 2) :: Row(0, 3) :: Nil)
      checkDelete(Some("key = 2"), Row(0, 3) :: Nil)
      checkDelete(Some("key = 0"), Nil)
    }
  }

  Seq(true, false).foreach { isPartitioned =>
    test(s"where data columns - Partition=$isPartitioned") {
      val partitions = if (isPartitioned) "key" :: Nil else Nil
      append(Seq((2, 2), (1, 4), (1, 1), (0, 3)).toDF("key", "value"), partitions)

      checkDelete(Some("value <= 2"), Row(1, 4) :: Row(0, 3) :: Nil)
      checkDelete(Some("value = 3"), Row(1, 4) :: Nil)
      checkDelete(Some("value != 0"), Nil)
    }
  }

  test("where data columns and partition columns") {
    val input = Seq((2, 2), (1, 4), (1, 1), (0, 3)).toDF("key", "value")
    append(input, Seq("key"))

    checkDelete(Some("value = 4 and key = 3"),
      Row(2, 2) :: Row(1, 4) :: Row(1, 1) :: Row(0, 3) :: Nil)
    checkDelete(Some("value = 4 and key = 1"),
      Row(2, 2) :: Row(1, 1) :: Row(0, 3) :: Nil)
    checkDelete(Some("value = 2 or key = 1"),
      Row(0, 3) :: Nil)
    checkDelete(Some("key = 0 or value = 99"),
      Nil)
  }

  Seq(true, false).foreach { skippingEnabled =>
    Seq(true, false).foreach { isPartitioned =>
      test(s"data and partition columns - Partition=$isPartitioned Skipping=$skippingEnabled") {
        withSQLConf(DeltaSQLConf.DELTA_STATS_SKIPPING.key -> skippingEnabled.toString) {
          val partitions = if (isPartitioned) "key" :: Nil else Nil
          val input = Seq((2, 2), (1, 4), (1, 1), (0, 3)).toDF("key", "value")
          append(input, partitions)

          checkDelete(Some("value = 4 and key = 3"),
            Row(2, 2) :: Row(1, 4) :: Row(1, 1) :: Row(0, 3) :: Nil)
          checkDelete(Some("value = 4 and key = 1"),
            Row(2, 2) :: Row(1, 1) :: Row(0, 3) :: Nil)
          checkDelete(Some("value = 2 or key = 1"),
            Row(0, 3) :: Nil)
          checkDelete(Some("key = 0 or value = 99"),
            Nil)
        }
      }
    }
  }

  test("Negative case - non-Delta target") {
    Seq((1, 1), (0, 3), (1, 5)).toDF("key1", "value")
      .write.format("parquet").mode("append").save(tempPath)
    val e = intercept[AnalysisException] {
      executeDelete(target = s"delta.`$tempPath`")
    }.getMessage
    assert(e.contains("DELETE destination only supports Delta sources") ||
      e.contains("is not a Delta table") || e.contains("Incompatible format"))
  }

  test("Negative case - non-deterministic condition") {
    append(Seq((2, 2), (1, 4), (1, 1), (0, 3)).toDF("key", "value"))
    val e = intercept[AnalysisException] {
      executeDelete(target = s"delta.`$tempPath`", where = "rand() > 0.5")
    }.getMessage
    assert(e.contains("nondeterministic expressions are only allowed in"))
  }

  test("Negative case - DELETE the child directory") {
    append(Seq((2, 2), (3, 2)).toDF("key", "value"), partitionBy = "key" :: Nil)
    val e = intercept[AnalysisException] {
      executeDelete(target = s"delta.`$tempPath/key=2`", where = "value = 2")
    }.getMessage
    assert(e.contains("Expect a full scan of Delta sources, but found a partial scan"))
  }

  test("delete cached table by name") {
    withTable("cached_delta_table") {
      Seq((2, 2), (1, 4)).toDF("key", "value")
        .write.format("delta").saveAsTable("cached_delta_table")

      spark.table("cached_delta_table").cache()
      spark.table("cached_delta_table").collect()
      executeDelete(target = "cached_delta_table", where = "key = 2")
      checkAnswer(spark.table("cached_delta_table"), Row(1, 4) :: Nil)
    }
  }

  test("delete cached table by path") {
    Seq((2, 2), (1, 4)).toDF("key", "value")
      .write.mode("overwrite").format("delta").save(tempPath)
    spark.read.format("delta").load(tempPath).cache()
    spark.read.format("delta").load(tempPath).collect()
    executeDelete(s"delta.`$tempPath`", where = "key = 2")
    checkAnswer(spark.read.format("delta").load(tempPath), Row(1, 4) :: Nil)
  }

  Seq(true, false).foreach { isPartitioned =>
    test(s"condition having current_date - Partition=$isPartitioned") {
      val partitions = if (isPartitioned) "key" :: Nil else Nil
      append(
        Seq((java.sql.Date.valueOf("1969-12-31"), 2),
          (java.sql.Date.valueOf("2099-12-31"), 4))
          .toDF("key", "value"), partitions)

      checkDelete(Some("CURRENT_DATE > key"),
        Row(java.sql.Date.valueOf("2099-12-31"), 4) :: Nil)
      checkDelete(Some("CURRENT_DATE <= key"), Nil)
    }
  }

  test("condition having current_timestamp - Partition by Timestamp") {
    append(
      Seq((java.sql.Timestamp.valueOf("2012-12-31 16:00:10.011"), 2),
        (java.sql.Timestamp.valueOf("2099-12-31 16:00:10.011"), 4))
        .toDF("key", "value"), Seq("key"))

    checkDelete(Some("CURRENT_TIMESTAMP > key"),
      Row(java.sql.Timestamp.valueOf("2099-12-31 16:00:10.011"), 4) :: Nil)
    checkDelete(Some("CURRENT_TIMESTAMP <= key"), Nil)
  }

  Seq(true, false).foreach { isPartitioned =>
    test(s"foldable condition - Partition=$isPartitioned") {
      val partitions = if (isPartitioned) "key" :: Nil else Nil
      append(Seq((2, 2), (1, 4), (1, 1), (0, 3)).toDF("key", "value"), partitions)

      val allRows = Row(2, 2) :: Row(1, 4) :: Row(1, 1) :: Row(0, 3) :: Nil

      checkDelete(Some("false"), allRows)
      checkDelete(Some("1 <> 1"), allRows)
      checkDelete(Some("1 > null"), allRows)
      checkDelete(Some("true"), Nil)
      checkDelete(Some("1 = 1"), Nil)
    }
  }

  test("SC-12232: should not delete the rows where condition evaluates to null") {
    append(Seq(("a", null), ("b", null), ("c", "v"), ("d", "vv")).toDF("key", "value").coalesce(1))

    // "null = null" evaluates to null
    checkDelete(Some("value = null"),
      Row("a", null) :: Row("b", null) :: Row("c", "v") :: Row("d", "vv") :: Nil)

    // these expressions evaluate to null when value is null
    checkDelete(Some("value = 'v'"),
      Row("a", null) :: Row("b", null) :: Row("d", "vv") :: Nil)
    checkDelete(Some("value <> 'v'"),
      Row("a", null) :: Row("b", null) :: Nil)
  }

  test("SC-12232: delete rows with null values using isNull") {
    append(Seq(("a", null), ("b", null), ("c", "v"), ("d", "vv")).toDF("key", "value").coalesce(1))

    // when value is null, this expression evaluates to true
    checkDelete(Some("value is null"),
      Row("c", "v") :: Row("d", "vv") :: Nil)
  }

  test("SC-12232: delete rows with null values using EqualNullSafe") {
    append(Seq(("a", null), ("b", null), ("c", "v"), ("d", "vv")).toDF("key", "value").coalesce(1))

    // when value is null, this expression evaluates to true
    checkDelete(Some("value <=> null"),
      Row("c", "v") :: Row("d", "vv") :: Nil)
  }

  test("do not support subquery test") {
    append(Seq((2, 2), (1, 4), (1, 1), (0, 3)).toDF("key", "value"))
    Seq((2, 2), (1, 4), (1, 1), (0, 3)).toDF("c", "d").createOrReplaceTempView("source")

    // basic subquery
    val e0 = intercept[AnalysisException] {
      executeDelete(target = s"delta.`$tempPath`", "key < (SELECT max(c) FROM source)")
    }.getMessage
    assert(e0.contains("Subqueries are not supported"))

    // subquery with EXISTS
    val e1 = intercept[AnalysisException] {
      executeDelete(target = s"delta.`$tempPath`", "EXISTS (SELECT max(c) FROM source)")
    }.getMessage
    assert(e1.contains("Subqueries are not supported"))

    // subquery with NOT EXISTS
    val e2 = intercept[AnalysisException] {
      executeDelete(target = s"delta.`$tempPath`", "NOT EXISTS (SELECT max(c) FROM source)")
    }.getMessage
    assert(e2.contains("Subqueries are not supported"))

    // subquery with IN
    val e3 = intercept[AnalysisException] {
      executeDelete(target = s"delta.`$tempPath`", "key IN (SELECT max(c) FROM source)")
    }.getMessage
    assert(e3.contains("Subqueries are not supported"))

    // subquery with NOT IN
    val e4 = intercept[AnalysisException] {
      executeDelete(target = s"delta.`$tempPath`", "key NOT IN (SELECT max(c) FROM source)")
    }.getMessage
    assert(e4.contains("Subqueries are not supported"))
  }
}
