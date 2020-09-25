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
import java.lang.{Integer => JInt}
import java.util.Locale

import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.scalatest.BeforeAndAfterEach

import org.apache.spark.sql.{AnalysisException, DataFrame, QueryTest, Row}
import org.apache.spark.sql.execution.adaptive.DisableAdaptiveExecution
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.{SharedSparkSession, SQLTestUtils}
import org.apache.spark.sql.types.{IntegerType, MapType, StringType, StructType}
import org.apache.spark.util.Utils

abstract class MergeIntoSuiteBase
    extends QueryTest
    with SharedSparkSession
    with BeforeAndAfterEach
    with SQLTestUtils {

  import testImplicits._

  protected var tempDir: File = _

  protected def tempPath: String = tempDir.getCanonicalPath

  override def beforeEach() {
    super.beforeEach()
    tempDir = Utils.createTempDir()
  }

  override def afterEach() {
    try {
      Utils.deleteRecursively(tempDir)
    } finally {
      super.afterEach()
    }
  }

  protected def executeMerge(
      target: String,
      source: String,
      condition: String,
      update: String,
      insert: String): Unit

  protected def executeMerge(
      tgt: String,
      src: String,
      cond: String,
      clauses: MergeClause*): Unit

  protected def append(df: DataFrame, partitions: Seq[String] = Nil): Unit = {
    val dfw = df.write.format("delta").mode("append")
    if (partitions.nonEmpty) {
      dfw.partitionBy(partitions: _*)
    }
    dfw.save(tempPath)
  }

  protected def readDeltaTable(path: String): DataFrame = {
    spark.read.format("delta").load(path)
  }

  protected def withCrossJoinEnabled(body: => Unit): Unit = {
    withSQLConf(SQLConf.CROSS_JOINS_ENABLED.key -> "true") { body }
  }

  Seq(true, false).foreach { isPartitioned =>
    test(s"basic case - merge to Delta table by path, isPartitioned: $isPartitioned") {
      withTable("source") {
        val partitions = if (isPartitioned) "key2" :: Nil else Nil
        append(Seq((2, 2), (1, 4)).toDF("key2", "value"), partitions)
        Seq((1, 1), (0, 3)).toDF("key1", "value").createOrReplaceTempView("source")

        executeMerge(
          target = s"delta.`$tempPath`",
          source = "source src",
          condition = "src.key1 = key2",
          update = "key2 = 20 + key1, value = 20 + src.value",
          insert = "(key2, value) VALUES (key1 - 10, src.value + 10)")

        checkAnswer(readDeltaTable(tempPath),
          Row(2, 2) :: // No change
            Row(21, 21) :: // Update
            Row(-10, 13) :: // Insert
            Nil)
      }
    }
  }

  Seq(true, false).foreach { skippingEnabled =>
    Seq(true, false).foreach { isPartitioned =>
      test("basic case - merge to view on a Delta table by path, " +
          s"isPartitioned: $isPartitioned skippingEnabled: $skippingEnabled") {
        withTable("delta_target", "source") {
          withSQLConf(DeltaSQLConf.DELTA_STATS_SKIPPING.key -> skippingEnabled.toString) {
            Seq((1, 1), (0, 3), (1, 6)).toDF("key1", "value").createOrReplaceTempView("source")
            val partitions = if (isPartitioned) "key2" :: Nil else Nil
            append(Seq((2, 2), (1, 4)).toDF("key2", "value"), partitions)
            readDeltaTable(tempPath).createOrReplaceTempView("delta_target")

            executeMerge(
              target = "delta_target",
              source = "source src",
              condition = "src.key1 = key2 AND src.value < delta_target.value",
              update = "key2 = 20 + key1, value = 20 + src.value",
              insert = "(key2, value) VALUES (key1 - 10, src.value + 10)")

            checkAnswer(sql("SELECT key2, value FROM delta_target"),
              Row(2, 2) :: // No change
                Row(21, 21) :: // Update
                Row(-10, 13) :: // Insert
                Row(-9, 16) :: // Insert
                Nil)
          }
        }
      }
    }
  }

  Seq(true, false).foreach { skippingEnabled =>
    Seq(true, false).foreach { isPartitioned =>
     test("basic case edge - merge to Delta table by name, " +
         s"isPartitioned: $isPartitioned skippingEnabled: $skippingEnabled") {
        withTable("delta_target", "source") {
          withSQLConf(DeltaSQLConf.DELTA_STATS_SKIPPING.key -> skippingEnabled.toString) {
            Seq((1, 1), (0, 3), (1, 6)).toDF("key1", "value").createOrReplaceTempView("source")
            val partitionByClause = if (isPartitioned) "PARTITIONED BY (key2)" else ""
            sql(
              s"""
                |CREATE TABLE delta_target(key2 INT, value INT)
                |USING delta
                |OPTIONS('path'='$tempPath')
                |$partitionByClause
               """.stripMargin)

            append(Seq((2, 2), (1, 4)).toDF("key2", "value"))

            executeMerge(
              target = "delta_target",
              source = "source src",
              condition = "src.key1 = key2 AND src.value < delta_target.value",
              update = "key2 = 20 + key1, value = 20 + src.value",
              insert = "(key2, value) VALUES (key1 - 10, src.value + 10)")

            checkAnswer(sql("SELECT key2, value FROM delta_target"),
              Row(2, 2) :: // No change
                Row(21, 21) :: // Update
                Row(-10, 13) :: // Insert
                Row(-9, 16) :: // Insert
                Nil)
            }
        }
      }
    }
  }

  test("basic case - update value from both source and target table") {
    withTable("source") {
      append(Seq((2, 2), (1, 4)).toDF("key2", "value"))
      Seq((1, 1), (0, 3)).toDF("key1", "value").createOrReplaceTempView("source")

      executeMerge(
        target = s"delta.`$tempPath` as trgNew",
        source = "source src",
        condition = "src.key1 = key2",
        update = "key2 = 20 + key2, value = trgNew.value + src.value",
        insert = "(key2, value) VALUES (key1 - 10, src.value + 10)")

      checkAnswer(readDeltaTable(tempPath),
        Row(2, 2) :: // No change
          Row(21, 5) :: // Update
          Row(-10, 13) :: // Insert
          Nil)
    }
  }

  test("basic case - columns are specified in wrong order") {
    withTable("source") {
      append(Seq((2, 2), (1, 4)).toDF("key2", "value"))
      Seq((1, 1), (0, 3)).toDF("key1", "value").createOrReplaceTempView("source")

      executeMerge(
        target = s"delta.`$tempPath` as trgNew",
        source = "source src",
        condition = "src.key1 = key2",
        update = "value = trgNew.value + src.value, key2 = 20 + key2",
        insert = "(value, key2) VALUES (src.value + 10, key1 - 10)")

      checkAnswer(readDeltaTable(tempPath),
        Row(2, 2) :: // No change
          Row(21, 5) :: // Update
          Row(-10, 13) :: // Insert
          Nil)
    }
  }

  test("basic case - not all columns are specified in update") {
    withTable("source") {
      append(Seq((2, 2), (1, 4)).toDF("key2", "value"))
      Seq((1, 1), (0, 3)).toDF("key1", "value").createOrReplaceTempView("source")

      executeMerge(
        target = s"delta.`$tempPath` as trgNew",
        source = "source src",
        condition = "src.key1 = key2",
        update = "value = trgNew.value + 3",
        insert = "(key2, value) VALUES (key1 - 10, src.value + 10)")

      checkAnswer(readDeltaTable(tempPath),
        Row(2, 2) :: // No change
          Row(1, 7) :: // Update
          Row(-10, 13) :: // Insert
          Nil)
    }
  }

  protected def testNullCase(name: String)(
      target: Seq[(JInt, JInt)],
      source: Seq[(JInt, JInt)],
      condition: String,
      expectedResults: Seq[(JInt, JInt)]) = {
    Seq(true, false).foreach { isPartitioned =>
      test(s"basic case - null handling - $name, isPartitioned: $isPartitioned") {
        withView("sourceView") {
          val partitions = if (isPartitioned) "key" :: Nil else Nil
          append(target.toDF("key", "value"), partitions)
          source.toDF("key", "value").createOrReplaceTempView("sourceView")

          executeMerge(
            target = s"delta.`$tempPath` as t",
            source = "sourceView s",
            condition = condition,
            update = "t.value = s.value",
            insert = "(t.key, t.value) VALUES (s.key, s.value)")

          checkAnswer(
            readDeltaTable(tempPath),
            expectedResults.map { r => Row(r._1, r._2) }
          )

          Utils.deleteRecursively(new File(tempPath))
        }
      }
    }
  }

  testNullCase("null value in target")(
    target = Seq((null, null), (1, 1)),
    source = Seq((1, 10), (2, 20)),
    condition = "s.key = t.key",
    expectedResults = Seq(
      (null, null),   // No change
      (1, 10),        // Update
      (2, 20)         // Insert
    ))

  testNullCase("null value in source")(
    target = Seq((1, 1)),
    source = Seq((1, 10), (2, 20), (null, null)),
    condition = "s.key = t.key",
    expectedResults = Seq(
      (1, 10),        // Update
      (2, 20),        // Insert
      (null, null)    // Insert
    ))

  testNullCase("null value in both source and target")(
    target = Seq((1, 1), (null, null)),
    source = Seq((1, 10), (2, 20), (null, 0)),
    condition = "s.key = t.key",
    expectedResults = Seq(
      (null, null),   // No change as null in source does not match null in target
      (1, 10),        // Update
      (2, 20),        // Insert
      (null, 0)       // Insert
    ))

  testNullCase("null value in both source and target + IS NULL in condition")(
    target = Seq((1, 1), (null, null)),
    source = Seq((1, 10), (2, 20), (null, 0)),
    condition = "s.key = t.key AND s.key IS NULL",
    expectedResults = Seq(
      (null, null),   // No change as s.key != t.key
      (1, 1),         // No change as s.key is not null
      (null, 0),      // Insert
      (1, 10),        // Insert
      (2, 20)         // Insert
    ))

  testNullCase("null value in both source and target + IS NOT NULL in condition")(
    target = Seq((1, 1), (null, null)),
    source = Seq((1, null), (2, 20), (null, 0)),
    condition = "s.key = t.key AND t.value IS NOT NULL",
    expectedResults = Seq(
      (null, null),   // No change as t.value is null
      (1, null),      // Update as t.value is not null
      (null, 0),      // Insert
      (2, 20)         // Insert
    ))

  testNullCase("null value in both source and target + <=> in condition")(
    target = Seq((1, 1), (null, null)),
    source = Seq((1, 10), (2, 20), (null, 0)),
    condition = "s.key <=> t.key",
    expectedResults = Seq(
      (null, 0),      // Update
      (1, 10),        // Update
      (2, 20)         // Insert
    ))

  testNullCase("NULL in condition")(
    target = Seq((1, 1), (null, null)),
    source = Seq((1, 10), (2, 20), (null, 0)),
    condition = "s.key = t.key AND NULL",
    expectedResults = Seq(
      (null, null),   // No change as NULL condition did not match anything
      (1, 1),         // No change as NULL condition did not match anything
      (null, 0),      // Insert
      (1, 10),        // Insert
      (2, 20)         // Insert
    ))

  test("basic case - only insert") {
    withTable("source") {
      Seq((5, 5)).toDF("key1", "value").createOrReplaceTempView("source")
      append(Seq.empty[(Int, Int)].toDF("key2", "value"))

      executeMerge(
        target = s"delta.`$tempPath` as target",
        source = "source src",
        condition = "src.key1 = target.key2",
        update = "key2 = 20 + key1, value = 20 + src.value",
        insert = "(key2, value) VALUES (key1 - 10, src.value + 10)")

      checkAnswer(readDeltaTable(tempPath),
        Row(-5, 15) :: // Insert
          Nil)
    }
  }

  test("basic case - both source and target are empty") {
    withTable("source") {
      Seq.empty[(Int, Int)].toDF("key1", "value").createOrReplaceTempView("source")
      append(Seq.empty[(Int, Int)].toDF("key2", "value"))

      executeMerge(
        target = s"delta.`$tempPath` as target",
        source = "source src",
        condition = "src.key1 = target.key2",
        update = "key2 = 20 + key1, value = 20 + src.value",
        insert = "(key2, value) VALUES (key1 - 10, src.value + 10)")

      checkAnswer(readDeltaTable(tempPath), Nil)
    }
  }

  test("basic case - only update") {
    withTable("source") {
      Seq((1, 5), (2, 9)).toDF("key1", "value").createOrReplaceTempView("source")
      append(Seq((2, 2), (1, 4)).toDF("key2", "value"))

      executeMerge(
        target = s"delta.`$tempPath` as target",
        source = "source src",
        condition = "src.key1 = target.key2",
        update = "key2 = 20 + key1, value = 20 + src.value",
        insert = "(key2, value) VALUES (key1 - 10, src.value + 10)")

      checkAnswer(readDeltaTable(tempPath),
        Row(21, 25) ::   // Update
          Row(22, 29) :: // Update
          Nil)
    }
  }

  test("same column names in source and target") {
    withTable("source") {
      Seq((1, 5), (2, 9)).toDF("key", "value").createOrReplaceTempView("source")
      append(Seq((2, 2), (1, 4)).toDF("key", "value"))

      executeMerge(
        target = s"delta.`$tempPath` as target",
        source = "source src",
        condition = "src.key = target.key",
        update = "target.key = 20 + src.key, target.value = 20 + src.value",
        insert = "(key, value) VALUES (src.key - 10, src.value + 10)")

      checkAnswer(
        readDeltaTable(tempPath),
        Row(21, 25) :: // Update
          Row(22, 29) :: // Update
          Nil)
    }
  }

  test("Source is a query") {
    withTable("source") {
      Seq((1, 6, "a"), (0, 3, "b")).toDF("key1", "value", "others")
        .createOrReplaceTempView("source")
      append(Seq((2, 2), (1, 4)).toDF("key2", "value"))

      executeMerge(
        target = s"delta.`$tempPath` as trg",
        source = "(SELECT key1, value, others FROM source) src",
        condition = "src.key1 = trg.key2",
        update = "trg.key2 = 20 + key1, value = 20 + src.value",
        insert = "(trg.key2, value) VALUES (key1 - 10, src.value + 10)")

      checkAnswer(
        readDeltaTable(tempPath),
        Row(2, 2) :: // No change
          Row(21, 26) :: // Update
          Row(-10, 13) :: // Insert
          Nil)

      withCrossJoinEnabled {
        executeMerge(
        target = s"delta.`$tempPath` as trg",
        source = "(SELECT 5 as key1, 5 as value) src",
        condition = "src.key1 = trg.key2",
        update = "trg.key2 = 20 + key1, value = 20 + src.value",
        insert = "(trg.key2, value) VALUES (key1 - 10, src.value + 10)")
      }

      checkAnswer(readDeltaTable(tempPath),
        Row(2, 2) ::
          Row(21, 26) ::
          Row(-10, 13) ::
          Row(-5, 15) :: // new row
          Nil)
    }
  }

  test("self merge") {
    append(Seq((2, 2), (1, 4)).toDF("key2", "value"))

    executeMerge(
      target = s"delta.`$tempPath` as target",
      source = s"delta.`$tempPath` as src",
      condition = "src.key2 = target.key2",
      update = "key2 = 20 + src.key2, value = 20 + src.value",
      insert = "(key2, value) VALUES (src.key2 - 10, src.value + 10)")

    checkAnswer(readDeltaTable(tempPath),
      Row(22, 22) :: // UPDATE
        Row(21, 24) :: // UPDATE
        Nil)
  }

  test("order by + limit in source query #1") {
    withTable("source") {
      Seq((1, 6, "a"), (0, 3, "b")).toDF("key1", "value", "others")
        .createOrReplaceTempView("source")
      append(Seq((2, 2), (1, 4)).toDF("key2", "value"))

      executeMerge(
        target = s"delta.`$tempPath` as trg",
        source = "(SELECT key1, value, others FROM source order by key1 limit 1) src",
        condition = "src.key1 = trg.key2",
        update = "trg.key2 = 20 + key1, value = 20 + src.value",
        insert = "(trg.key2, value) VALUES (key1 - 10, src.value + 10)")

      checkAnswer(readDeltaTable(tempPath),
        Row(1, 4) :: // No change
          Row(2, 2) :: // No change
          Row(-10, 13) :: // Insert
          Nil)
    }
  }

  test("order by + limit in source query #2") {
    withTable("source") {
      Seq((1, 6, "a"), (0, 3, "b")).toDF("key1", "value", "others")
        .createOrReplaceTempView("source")
      append(Seq((2, 2), (1, 4)).toDF("key2", "value"))

      executeMerge(
        target = s"delta.`$tempPath` as trg",
        source = "(SELECT key1, value, others FROM source order by value DESC limit 1) src",
        condition = "src.key1 = trg.key2",
        update = "trg.key2 = 20 + key1, value = 20 + src.value",
        insert = "(trg.key2, value) VALUES (key1 - 10, src.value + 10)")

      checkAnswer(readDeltaTable(tempPath),
        Row(2, 2) :: // No change
          Row(21, 26) :: // UPDATE
          Nil)
    }
  }

  testQuietly("Negative case - more than one source rows match the same target row") {
    withTable("source") {
      Seq((1, 1), (0, 3), (1, 5)).toDF("key1", "value").createOrReplaceTempView("source")
      append(Seq((2, 2), (1, 4)).toDF("key2", "value"))

      val e = intercept[Exception] {
        executeMerge(
          target = s"delta.`$tempPath` as target",
          source = "source src",
          condition = "src.key1 = target.key2",
          update = "key2 = 20 + key1, value = 20 + src.value",
          insert = "(key2, value) VALUES (key1 - 10, src.value + 10)")
      }.toString

      val expectedEx = DeltaErrors.multipleSourceRowMatchingTargetRowInMergeException(spark)
      assert(e.contains(expectedEx.getMessage))
    }
  }

  test("More than one target rows match the same source row") {
    withTable("source") {
      Seq((1, 5), (2, 9)).toDF("key1", "value").createOrReplaceTempView("source")
      append(Seq((2, 2), (1, 4)).toDF("key2", "value"), Seq("key2"))

      withCrossJoinEnabled {
        executeMerge(
          target = s"delta.`$tempPath` as target",
          source = "source src",
          condition = "key1 = 1",
          update = "key2 = 20 + key1, value = 20 + src.value",
          insert = "(key2, value) VALUES (key1 - 10, src.value + 10)")
      }

      checkAnswer(readDeltaTable(tempPath),
        Row(-8, 19) :: // Insert
          Row(21, 25) :: // Update
          Row(21, 25) :: // Update
          Nil)
    }
  }

  Seq(true, false).foreach { isPartitioned =>
    test(s"Merge table using different data types - implicit casting, parts: $isPartitioned") {
      withTable("source") {
        Seq((1, "5"), (3, "9"), (3, "a")).toDF("key1", "value").createOrReplaceTempView("source")
        val partitions = if (isPartitioned) "key2" :: Nil else Nil
        append(Seq((2, 2), (1, 4)).toDF("key2", "value"), partitions)

        executeMerge(
          target = s"delta.`$tempPath` as target",
          source = "source src",
          condition = "key1 = key2",
          update = "key2 = '33' + key2, value = '20'",
          insert = "(key2, value) VALUES ('44', src.value + '10')")

        checkAnswer(readDeltaTable(tempPath),
          Row(44, 19) :: // Insert
          // NULL is generated when the type casting does not work for some values)
          Row(44, null) :: // Insert
          Row(34, 20) :: // Update
          Row(2, 2) :: // No change
          Nil)
      }
    }
  }

  def errorContains(errMsg: String, str: String): Unit = {
    assert(errMsg.toLowerCase(Locale.ROOT).contains(str.toLowerCase(Locale.ROOT)))
  }

  def errorNotContains(errMsg: String, str: String): Unit = {
    assert(!errMsg.toLowerCase(Locale.ROOT).contains(str.toLowerCase(Locale.ROOT)))
  }


  test("Negative case - basic syntax analysis") {
    withTable("source") {
      Seq((1, 1), (0, 3), (1, 5)).toDF("key1", "value").createOrReplaceTempView("source")
      append(Seq((2, 2), (1, 4)).toDF("key2", "value"))

      // insert expressions have target table reference
      var e = intercept[AnalysisException] {
        executeMerge(
          target = s"delta.`$tempPath` as target",
          source = "source src",
          condition = "src.key1 = target.key2",
          update = "key2 = key1, value = src.value",
          insert = "(key2, value) VALUES (3, src.value + key2)")
      }.getMessage

      errorContains(e, "cannot resolve `key2`")

      // to-update columns have source table reference
      e = intercept[AnalysisException] {
        executeMerge(
          target = s"delta.`$tempPath` as target",
          source = "source src",
          condition = "src.key1 = target.key2",
          update = "key1 = 1, value = 2",
          insert = "(key2, value) VALUES (3, 4)")
      }.getMessage

      errorContains(e, "Cannot resolve `key1` in UPDATE clause")
      errorContains(e, "key2") // should show key2 as a valid name in target columns

      // to-insert columns have source table reference
      e = intercept[AnalysisException] {
        executeMerge(
          target = s"delta.`$tempPath` as target",
          source = "source src",
          condition = "src.key1 = target.key2",
          update = "key2 = 1, value = 2",
          insert = "(key1, value) VALUES (3, 4)")
      }.getMessage

      errorContains(e, "Cannot resolve `key1` in INSERT clause")
      errorContains(e, "key2") // should contain key2 as a valid name in target columns

      // ambiguous reference
      e = intercept[AnalysisException] {
        executeMerge(
          target = s"delta.`$tempPath` as target",
          source = "source src",
          condition = "src.key1 = target.key2",
          update = "key2 = 1, value = value",
          insert = "(key2, value) VALUES (3, 4)")
      }.getMessage

      errorContains(e, "Reference 'value' is ambiguous, could be: ")

      // non-deterministic search condition
      e = intercept[AnalysisException] {
        executeMerge(
          target = s"delta.`$tempPath` as target",
          source = "source src",
          condition = "src.key1 = target.key2 and rand() > 0.5",
          update = "key2 = 1, value = 2",
          insert = "(key2, value) VALUES (3, 4)")
      }.getMessage

      errorContains(e, "Non-deterministic functions are not supported in the search condition")

      // aggregate function
      e = intercept[AnalysisException] {
        executeMerge(
          target = s"delta.`$tempPath` as target",
          source = "source src",
          condition = "src.key1 = target.key2 and max(target.key2) > 20",
          update = "key2 = 1, value = 2",
          insert = "(key2, value) VALUES (3, 4)")
      }.getMessage

      errorContains(e, "Aggregate functions are not supported in the search condition")
    }
  }

  test("Negative case - non-delta target") {
    withTable("source", "target") {
      Seq((1, 1), (0, 3), (1, 5)).toDF("key1", "value").createOrReplaceTempView("source")
      Seq((1, 1), (0, 3), (1, 5)).toDF("key2", "value").write.saveAsTable("target")

      val e = intercept[AnalysisException] {
        executeMerge(
          target = "target",
          source = "source src",
          condition = "src.key1 = target.key2",
          update = "key2 = 20 + key1, value = 20 + src.value",
          insert = "(key2, value) VALUES (key1 - 10, src.value + 10)")
      }.getMessage
      errorContains(e, "MERGE destination only supports Delta sources")
    }
  }

  test("Negative case - update assignments conflict because " +
    "same column with different references") {
    withTable("source") {
      Seq((1, 1), (0, 3), (1, 5)).toDF("key1", "value").createOrReplaceTempView("source")
      append(Seq((2, 2), (1, 4)).toDF("key2", "value"))
      val e = intercept[AnalysisException] {
        executeMerge(
          target = s"delta.`$tempPath` as t",
          source = "source s",
          condition = "s.key1 = t.key2",
          update = "key2 = key1, t.key2 = key1",
          insert = "(key2, value) VALUES (3, 4)")
      }.getMessage

      errorContains(e, "there is a conflict from these set columns")
    }
  }

  test("Negative case - more operations between merge and delta target") {
    withTempView("source", "target") {
      Seq((1, 1), (0, 3), (1, 5)).toDF("key1", "value").createOrReplaceTempView("source")
      append(Seq((2, 2), (1, 4)).toDF("key2", "value"))
      spark.read.format("delta").load(tempPath).filter("value <> 0").createTempView("target")

      val e = intercept[AnalysisException] {
        executeMerge(
          target = "target",
          source = "source src",
          condition = "src.key1 = target.key2",
          update = "key2 = 20 + key1, value = 20 + src.value",
          insert = "(key2, value) VALUES (key1 - 10, src.value + 10)")
      }.getMessage
      errorContains(e, "Expect a full scan of Delta sources, but found a partial scan")
    }
  }

  test("Negative case - MERGE to the child directory") {
    val df = Seq((1, 1), (0, 3), (1, 5)).toDF("key2", "value")
    val partitions = "key2" :: Nil
    append(df, partitions)

    val e = intercept[AnalysisException] {
      executeMerge(
        target = s"delta.`$tempPath/key2=1` target",
        source = "(SELECT 5 as key1, 5 as value) src",
        condition = "src.key1 = target.key2",
        update = "key2 = 20 + key1, value = 20 + src.value",
        insert = "(key2, value) VALUES (key1 - 10, src.value + 10)")
    }.getMessage
    errorContains(e, "Expect a full scan of Delta sources, but found a partial scan")
  }

  Seq(true, false).foreach { isPartitioned =>
    test(s"single file, isPartitioned: $isPartitioned") {
      withTable("source") {
        val df = spark.range(5).selectExpr("id as key1", "id as key2", "id as col1").repartition(1)

        val partitions = if (isPartitioned) "key1" :: "key2" :: Nil else Nil
        append(df, partitions)

        df.createOrReplaceTempView("source")

        executeMerge(
          target = s"delta.`$tempPath` target",
          source = "(SELECT key1 as srcKey, key2, col1 FROM source where key1 < 3) AS source",
          condition = "srcKey = target.key1",
          update = "target.key1 = srcKey - 1000, target.key2 = source.key2 + 1000, " +
            "target.col1 = source.col1",
          insert = "(key1, key2, col1) VALUES (srcKey, source.key2, source.col1)")

        checkAnswer(readDeltaTable(tempPath),
          Row(-998, 1002, 2) :: // Update
            Row(-999, 1001, 1) :: // Update
            Row(-1000, 1000, 0) :: // Update
            Row(4, 4, 4) :: // No change
            Row(3, 3, 3) :: // No change
            Nil)
      }
    }
  }

  protected def testLocalPredicates(name: String)(
      target: Seq[(String, String, String)],
      source: Seq[(String, String)],
      condition: String,
      expectedResults: Seq[(String, String, String)],
      numFilesPerPartition: Int = 2) = {
    Seq(true, false).foreach { isPartitioned =>
      test(s"$name, isPartitioned: $isPartitioned") { withTable("source") {
        val partitions = if (isPartitioned) "key2" :: Nil else Nil
        append(target.toDF("key2", "value", "op").repartition(numFilesPerPartition), partitions)
        source.toDF("key1", "value").createOrReplaceTempView("source")

        // Local predicates are likely to be pushed down leading empty join conditions
        // and cross-join being used
        withCrossJoinEnabled { executeMerge(
          target = s"delta.`$tempPath` trg",
          source = "source src",
          condition = condition,
          update = "key2 = src.key1, value = src.value, op = 'update'",
          insert = "(key2, value, op) VALUES (src.key1, src.value, 'insert')")
        }

        checkAnswer(
          readDeltaTable(tempPath),
          expectedResults.map { r => Row(r._1, r._2, r._3) }
        )

        Utils.deleteRecursively(new File(tempPath))
      }
    }}
  }

  testLocalPredicates("basic case - local predicates - predicate has no matches, only inserts")(
    target = Seq(("2", "2", "noop"), ("1", "4", "noop"), ("3", "2", "noop"), ("4", "4", "noop")),
    source = Seq(("1", "8"), ("0", "3")),
    condition = "src.key1 = key2 and key2 != '1'",
    expectedResults =
      ("2", "2", "noop") ::
      ("1", "4", "noop") ::
      ("3", "2", "noop") ::
      ("4", "4", "noop") ::
      ("1", "8", "insert") ::
      ("0", "3", "insert") ::
      Nil)

  testLocalPredicates("basic case - local predicates - predicate has matches, updates and inserts")(
    target = Seq(("1", "2", "noop"), ("1", "4", "noop"), ("3", "2", "noop"), ("4", "4", "noop")),
    source = Seq(("1", "8"), ("0", "3")),
    condition = "src.key1 = key2 and key2 < '3'",
    expectedResults =
      ("3", "2", "noop") ::
      ("4", "4", "noop") ::
      ("1", "8", "update") ::
      ("1", "8", "update") ::
      ("0", "3", "insert") ::
      Nil)

  testLocalPredicates("basic case - local predicates - predicate has matches, only updates")(
    target = Seq(("1", "2", "noop"), ("1", "4", "noop"), ("3", "2", "noop"), ("4", "4", "noop")),
    source = Seq(("1", "8")),
    condition = "key2 < '3'",
    expectedResults =
      ("3", "2", "noop") ::
      ("4", "4", "noop") ::
      ("1", "8", "update") ::
      ("1", "8", "update") ::
      Nil)

  testLocalPredicates("basic case - local predicates - always false predicate, only inserts")(
      target = Seq(("1", "2", "noop"), ("1", "4", "noop"), ("3", "2", "noop"), ("4", "4", "noop")),
      source = Seq(("1", "8"), ("0", "3")),
      condition = "1 != 1",
      expectedResults =
        ("1", "2", "noop") ::
        ("1", "4", "noop") ::
        ("3", "2", "noop") ::
        ("4", "4", "noop") ::
        ("1", "8", "insert") ::
        ("0", "3", "insert") ::
        Nil)

  testLocalPredicates("basic case - local predicates - always true predicate, all updated")(
    target = Seq(("1", "2", "noop"), ("1", "4", "noop"), ("3", "2", "noop"), ("4", "4", "noop")),
    source = Seq(("1", "8")),
    condition = "1 = 1",
    expectedResults =
      ("1", "8", "update") ::
      ("1", "8", "update") ::
      ("1", "8", "update") ::
      ("1", "8", "update") ::
      Nil)

  testLocalPredicates("basic case - local predicates - single file, updates and inserts")(
    target = Seq(("1", "2", "noop"), ("1", "4", "noop"), ("3", "2", "noop"), ("4", "4", "noop")),
    source = Seq(("1", "8"), ("3", "10"), ("0", "3")),
    condition = "src.key1 = key2 and key2 < '3'",
    expectedResults =
      ("3", "2", "noop") ::
      ("4", "4", "noop") ::
      ("1", "8", "update") ::
      ("1", "8", "update") ::
      ("0", "3", "insert") ::
      ("3", "10", "insert") ::
      Nil,
    numFilesPerPartition = 1
  )

  Seq(true, false).foreach { isPartitioned =>
    test(s"basic case - column pruning, isPartitioned: $isPartitioned") {
      withTable("source") {
        val partitions = if (isPartitioned) "key2" :: Nil else Nil
        append(Seq((2, 2), (1, 4)).toDF("key2", "value"), partitions)
        Seq((1, 1, "a"), (0, 3, "b")).toDF("key1", "value", "col1")
          .createOrReplaceTempView("source")

        executeMerge(
          target = s"delta.`$tempPath`",
          source = "source src",
          condition = "src.key1 = key2",
          update = "key2 = 20 + key1, value = 20 + src.value",
          insert = "(key2, value) VALUES (key1 - 10, src.value + 10)")

        checkAnswer(readDeltaTable(tempPath),
          Row(2, 2) :: // No change
            Row(21, 21) :: // Update
            Row(-10, 13) :: // Insert
            Nil)
      }
    }
  }

  protected def withKeyValueData(
      source: Seq[(Int, Int)],
      target: Seq[(Int, Int)],
      isKeyPartitioned: Boolean = false,
      sourceKeyValueNames: (String, String) = ("key", "value"),
      targetKeyValueNames: (String, String) = ("key", "value"))(
      thunk: (String, String) => Unit = null): Unit = {

    append(target.toDF(targetKeyValueNames._1, targetKeyValueNames._2),
      if (isKeyPartitioned) Seq(targetKeyValueNames._1) else Nil)
    withTempView("source") {
      source.toDF(sourceKeyValueNames._1, sourceKeyValueNames._2).createOrReplaceTempView("source")
      thunk("source", s"delta.`$tempPath`")
    }
  }

  test("merge into cached table edge") {
    // Merge with a cached target only works in the join-based implementation right now
    withTable("source") {
      append(Seq((2, 2), (1, 4)).toDF("key2", "value"))
      Seq((1, 1), (0, 3), (3, 3)).toDF("key1", "value").createOrReplaceTempView("source")
      spark.table(s"delta.`$tempPath`").cache()
      spark.table(s"delta.`$tempPath`").collect()

      append(Seq((100, 100), (3, 5)).toDF("key2", "value"))
      // cache is in effect, as the above change is not reflected
      checkAnswer(spark.table(s"delta.`$tempPath`"), Row(2, 2) :: Row(1, 4) :: Nil)

      executeMerge(
        target = s"delta.`$tempPath` as trgNew",
        source = "source src",
        condition = "src.key1 = key2",
        update = "value = trgNew.value + 3",
        insert = "(key2, value) VALUES (key1, src.value + 10)")

      checkAnswer(spark.table(s"delta.`$tempPath`"),
        Row(100, 100) :: // No change (newly inserted record)
          Row(2, 2) :: // No change
          Row(1, 7) :: // Update
          Row(3, 8) :: // Update (on newly inserted record)
          Row(0, 13) :: // Insert
          Nil)
    }
  }

  def testNestedDataSupport(name: String, namePrefix: String = "nested data support")(
      source: String,
      target: String,
      update: Seq[String],
      insert: String = null,
      schema: StructType = null,
      result: String = null,
      errorStrs: Seq[String] = null): Unit = {

    require(result == null ^ errorStrs == null, "either set the result or the error strings")

    val testName =
      if (result != null) s"$namePrefix - $name" else s"$namePrefix - analysis error - $name"

    test(testName) {
      withJsonData(source, target, schema) { case (sourceName, targetName) =>
        val fieldNames = spark.table(targetName).schema.fieldNames
        val fieldNamesStr = fieldNames.mkString("`", "`, `", "`")
        val keyName = s"`${fieldNames.head}`"

        def execMerge() = executeMerge(
          target = s"$targetName t",
          source = s"$sourceName s",
          condition = s"s.$keyName = t.$keyName",
          update = update.mkString(", "),
          insert = Option(insert).getOrElse(s"($fieldNamesStr) VALUES ($fieldNamesStr)"))

        if (result != null) {
          execMerge()
          checkAnswer(spark.table(targetName), spark.read.json(strToJsonSeq(result).toDS))
        } else {
          val e = intercept[AnalysisException] { execMerge() }
          errorStrs.foreach { s => errorContains(e.getMessage, s) }
        }
      }
    }
  }

  testNestedDataSupport("no update when not matched, only insert")(
    source = """
        { "key": { "x": "X3", "y": 3}, "value": { "a": 300, "b": "B300" } }""",
    target = """
        { "key": { "x": "X1", "y": 1}, "value": { "a": 1,   "b": "B1" } }
        { "key": { "x": "X2", "y": 2}, "value": { "a": 2,   "b": "B2" } }""",
    update = "value.b = 'UPDATED'" :: Nil,
    result = """
        { "key": { "x": "X1", "y": 1}, "value": { "a": 1,   "b": "B1" } }
        { "key": { "x": "X2", "y": 2}, "value": { "a": 2,   "b": "B2"      } }
        { "key": { "x": "X3", "y": 3}, "value": { "a": 300, "b": "B300"    } }""")

  testNestedDataSupport("update entire nested column")(
    source = """
        { "key": { "x": "X1", "y": 1}, "value": { "a": 100, "b": "B100" } }""",
    target = """
        { "key": { "x": "X1", "y": 1}, "value": { "a": 1,   "b": "B1" } }
        { "key": { "x": "X2", "y": 2}, "value": { "a": 2,   "b": "B2" } }""",
    update = "value = s.value" :: Nil,
    result = """
        { "key": { "x": "X1", "y": 1}, "value": { "a": 100, "b": "B100" } }
        { "key": { "x": "X2", "y": 2}, "value": { "a": 2, "b": "B2"   } }""")

  testNestedDataSupport("update one nested field")(
    source = """
        { "key": { "x": "X1", "y": 1}, "value": { "a": 100, "b": "B100" } }""",
    target = """
        { "key": { "x": "X1", "y": 1}, "value": { "a": 1,   "b": "B1" } }
        { "key": { "x": "X2", "y": 2}, "value": { "a": 2,   "b": "B2" } }""",
    update = "value.b = s.value.b" :: Nil,
    result = """
        { "key": { "x": "X1", "y": 1}, "value": { "a": 1, "b": "B100" } }
        { "key": { "x": "X2", "y": 2}, "value": { "a": 2, "b": "B2"   } }""")

  testNestedDataSupport("update multiple fields at different levels")(
    source = """
        { "key": { "x": "X1", "y": { "i": 1.0 } }, "value": { "a": 100, "b": "B100" } }""",
    target = """
        { "key": { "x": "X1", "y": { "i": 1.0 } }, "value": { "a": 1,   "b": "B1" } }
        { "key": { "x": "X2", "y": { "i": 2.0 } }, "value": { "a": 2,   "b": "B2" } }""",
    update =
      "key.x = 'XXX'" :: "key.y.i = 9000" ::
      "value = named_struct('a', 9000, 'b', s.value.b)" :: Nil,
    result = """
        { "key": { "x": "XXX", "y": { "i": 9000 } }, "value": { "a": 9000, "b": "B100" } }
        { "key": { "x": "X2" , "y": { "i": 2.0  } }, "value": { "a": 2, "b": "B2" } }""")

  testNestedDataSupport("update multiple fields at different levels to NULL")(
    source = """
        { "key": { "x": "X1", "y": { "i": 1.0 } }, "value": { "a": 100, "b": "B100" } }""",
    target = """
        { "key": { "x": "X1", "y": { "i": 1.0 } }, "value": { "a": 1,   "b": "B1" } }
        { "key": { "x": "X2", "y": { "i": 2.0 } }, "value": { "a": 2,   "b": "B2" } }""",
    update = "value = NULL" :: "key.x = NULL" :: "key.y.i = NULL" :: Nil,
    result = """
        { "key": { "x": null, "y": { "i" : null } }, "value": null }
        { "key": { "x": "X2" , "y": { "i" : 2.0  } }, "value": { "a": 2, "b": "B2" } }""")

  testNestedDataSupport("update multiple fields at different levels with implicit casting")(
    source = """
        { "key": { "x": "X1", "y": { "i": 1.0 } }, "value": { "a": 100, "b": "B100" } }""",
    target = """
        { "key": { "x": "X1", "y": { "i": 1.0 } }, "value": { "a": 1,   "b": "B1" } }
        { "key": { "x": "X2", "y": { "i": 2.0 } }, "value": { "a": 2,   "b": "B2" } }""",
    update =
      "key.x = 'XXX' " :: "key.y.i = '9000'" ::
      "value = named_struct('a', '9000', 'b', s.value.b)" :: Nil,
    result = """
        { "key": { "x": "XXX", "y": { "i": 9000 } }, "value": { "a": 9000, "b": "B100" } }
        { "key": { "x": "X2" , "y": { "i": 2.0  } }, "value": { "a": 2, "b": "B2" } }""")

  testNestedDataSupport("update array fields at different levels")(
    source = """
        { "key": { "x": "X1", "y": [ 1, 11 ] }, "value": [ -1, -10 , -100 ] }""",
    target = """
        { "key": { "x": "X1", "y": [ 1, 11 ] }, "value": [ 1, 10 , 100 ]} }
        { "key": { "x": "X2", "y": [ 2, 22 ] }, "value": [ 2, 20 , 200 ]} }""",
    update = "value = array(-9000)" :: "key.y = array(-1, -11)" :: Nil,
    result = """
        { "key": { "x": "X1", "y": [ -1, -11 ] }, "value": [ -9000 ]} }
        { "key": { "x": "X2", "y": [ 2, 22 ] }, "value": [ 2, 20 , 200 ]} }""")

  testNestedDataSupport("update using quoted names at different levels", "dotted name support")(
    source = """
        { "key": { "x": "X1", "y.i": 1.0 }, "value.a": "A" }""",
    target = """
        { "key": { "x": "X1", "y.i": 1.0 }, "value.a": "A1" }
        { "key": { "x": "X2", "y.i": 2.0 }, "value.a": "A2" }""",
    update = "`t`.key.`y.i` = 9000" ::  "t.`value.a` = 'UPDATED'" :: Nil,
    result = """
        { "key": { "x": "X1", "y.i": 9000 }, "value.a": "UPDATED" }
        { "key": { "x": "X2", "y.i" : 2.0 }, "value.a": "A2" }""")

  testNestedDataSupport("unknown nested field")(
    source = """{ "key": "A", "value": { "a": 0 } }""",
    target = """{ "key": "A", "value": { "a": 1 } }""",
    update = "value.c = 'UPDATED'" :: Nil,
    errorStrs = "No such struct field" :: Nil)

  testNestedDataSupport("assigning simple type to struct field")(
    source = """{ "key": "A", "value": { "a": { "x": 1 } } }""",
    target = """{ "key": "A", "value": { "a": { "x": 1 } } }""",
    update = "value.a = 'UPDATED'" :: Nil,
    errorStrs = "data type mismatch" :: Nil)

  testNestedDataSupport("conflicting assignments between two nested fields at different levels")(
    source = """{ "key": "A", "value": { "a": { "x": 0 } } }""",
    target = """{ "key": "A", "value": { "a": { "x": 1 } } }""",
    update = "value.a.x = 2" :: "value.a = named_struct('x', 3)" :: Nil,
    errorStrs = "There is a conflict from these SET columns" :: Nil)

  testNestedDataSupport("conflicting assignments between nested field and top-level column")(
    source = """{ "key": "A", "value": { "a": 0 } }""",
    target = """{ "key": "A", "value": { "a": 1 } }""",
    update = "value.a = 2" :: "value = named_struct('a', 3)" :: Nil,
    errorStrs = "There is a conflict from these SET columns" :: Nil)

  testNestedDataSupport("nested field not supported in INSERT")(
    source = """{ "key": "A", "value": { "a": 0 } }""",
    target = """{ "key": "B", "value": { "a": 1 } }""",
    update = "value.a = 2" :: Nil,
    insert = """(key, value.a) VALUES (s.key, s.value.a)""",
    errorStrs = "Nested field is not supported in the INSERT clause" :: Nil)

  testNestedDataSupport("updating map type")(
    source = """{ "key": "A", "value": { "a": 0 } }""",
    target = """{ "key": "A", "value": { "a": 1 } }""",
    update = "value.a = 2" :: Nil,
    schema = new StructType().add("key", StringType).add("value", MapType(StringType, IntegerType)),
    errorStrs = "Updating nested fields is only supported for StructType" :: Nil)

  testNestedDataSupport("updating array type")(
    source = """{ "key": "A", "value": [ { "a": 0 } ] }""",
    target = """{ "key": "A", "value": [ { "a": 1 } ] }""",
    update = "value.a = 2" :: Nil,
    schema = new StructType().add("key", StringType).add("value", MapType(StringType, IntegerType)),
    errorStrs = "Updating nested fields is only supported for StructType" :: Nil)

  /** A simple representative of a any WHEN clause in a MERGE statement */
  protected case class MergeClause(isMatched: Boolean, condition: String, action: String = null) {
    def sql: String = {
      assert(action != null, "action not specified yet")
      val matched = if (isMatched) "MATCHED" else "NOT MATCHED"
      val cond = if (condition != null) s"AND $condition" else ""
      s"WHEN $matched $cond THEN $action"
    }
  }

  protected def update(set: String = null, condition: String = null): MergeClause = {
    MergeClause(isMatched = true, condition, s"UPDATE SET $set")
  }

  protected def delete(condition: String = null): MergeClause = {
    MergeClause(isMatched = true, condition, s"DELETE")
  }

  protected def insert(values: String = null, condition: String = null): MergeClause = {
    MergeClause(isMatched = false, condition, s"INSERT $values")
  }

  protected def testAnalysisErrorsInExtendedMerge(
      name: String,
      namePrefix: String = "extended syntax")(
      mergeOn: String,
      mergeClauses: MergeClause*)(
      errorStrs: Seq[String],
      notErrorStrs: Seq[String] = Nil): Unit = {
    test(s"$namePrefix - analysis errors - $name") {
      withKeyValueData(
        source = Seq.empty,
        target = Seq.empty,
        sourceKeyValueNames = ("key", "srcValue"),
        targetKeyValueNames = ("key", "tgtValue")
      ) { case (sourceName, targetName) =>
        val errMsg = intercept[AnalysisException] {
          executeMerge(s"$targetName t", s"$sourceName s", mergeOn, mergeClauses: _*)
        }.getMessage
        errorStrs.foreach { s => errorContains(errMsg, s) }
        notErrorStrs.foreach { s => errorNotContains(errMsg, s) }
      }
    }
  }

  testAnalysisErrorsInExtendedMerge("update condition - ambiguous reference")(
    mergeOn = "s.key = t.key",
    update(condition = "key > 1", set = "tgtValue = srcValue"))(
    errorStrs = "reference 'key' is ambiguous" :: Nil)

  testAnalysisErrorsInExtendedMerge("update condition - unknown reference")(
    mergeOn = "s.key = t.key",
    update(condition = "unknownAttrib > 1", set = "tgtValue = srcValue"))(
    // Should show unknownAttrib as invalid ref and (key, tgtValue, srcValue) as valid column names.
    errorStrs = "UPDATE condition" :: "unknownAttrib" :: "key" :: "tgtValue" :: "srcValue" :: Nil)

  testAnalysisErrorsInExtendedMerge("update condition - aggregation function")(
    mergeOn = "s.key = t.key",
    update(condition = "max(0) > 0", set = "tgtValue = srcValue"))(
    errorStrs = "UPDATE condition" :: "aggregate functions are not supported" :: Nil)

  testAnalysisErrorsInExtendedMerge("update condition - subquery")(
    mergeOn = "s.key = t.key",
    update(condition = "s.value in (select value from t)", set = "tgtValue = srcValue"))(
    errorStrs = Nil) // subqueries fail for unresolved reference to `t`

  testAnalysisErrorsInExtendedMerge("delete condition - ambiguous reference")(
    mergeOn = "s.key = t.key",
    delete(condition = "key > 1"))(
    errorStrs = "reference 'key' is ambiguous" :: Nil)

  testAnalysisErrorsInExtendedMerge("delete condition - unknown reference")(
    mergeOn = "s.key = t.key",
    delete(condition = "unknownAttrib > 1"))(
    // Should show unknownAttrib as invalid ref and (key, tgtValue, srcValue) as valid column names.
    errorStrs = "DELETE condition" :: "unknownAttrib" :: "key" :: "tgtValue" :: "srcValue" :: Nil)

  testAnalysisErrorsInExtendedMerge("delete condition - aggregation function")(
    mergeOn = "s.key = t.key",
    delete(condition = "max(0) > 0"))(
    errorStrs = "DELETE condition" :: "aggregate functions are not supported" :: Nil)

  testAnalysisErrorsInExtendedMerge("delete condition - subquery")(
    mergeOn = "s.key = t.key",
    delete(condition = "s.srcValue in (select tgtValue from t)"))(
    errorStrs = Nil)  // subqueries fail for unresolved reference to `t`

  testAnalysisErrorsInExtendedMerge("insert condition - unknown reference")(
    mergeOn = "s.key = t.key",
    insert(condition = "unknownAttrib > 1", values = "(key, tgtValue) VALUES (s.key, s.srcValue)"))(
    // Should show unknownAttrib as invalid ref and (key, srcValue) as valid column names,
    // but not show tgtValue as a valid name as target columns cannot be present in insert clause.
    errorStrs = "INSERT condition" :: "unknownAttrib" :: "key" :: "srcValue" :: Nil,
    notErrorStrs = "tgtValue")

  testAnalysisErrorsInExtendedMerge("insert condition - reference to target table column")(
    mergeOn = "s.key = t.key",
    insert(condition = "tgtValue > 1", values = "(key, tgtValue) VALUES (s.key, s.srcValue)"))(
    // Should show tgtValue as invalid ref and (key, srcValue) as valid column names
    errorStrs = "INSERT condition" :: "tgtValue" :: "key" :: "srcValue" :: Nil)

  testAnalysisErrorsInExtendedMerge("insert condition - aggregation function")(
    mergeOn = "s.key = t.key",
    insert(condition = "max(0) > 0", values = "(key, tgtValue) VALUES (s.key, s.srcValue)"))(
    errorStrs = "INSERT condition" :: "aggregate functions are not supported" :: Nil)

  testAnalysisErrorsInExtendedMerge("insert condition - subquery")(
    mergeOn = "s.key = t.key",
    insert(
      condition = "s.srcValue in (select srcValue from s)",
      values = "(key, tgtValue) VALUES (s.key, s.srcValue)"))(
    errorStrs = Nil)  // subqueries fail for unresolved reference to `s`


  protected def testExtendedMerge(
      name: String,
      namePrefix: String = "extended syntax")(
      source: Seq[(Int, Int)],
      target: Seq[(Int, Int)],
      mergeOn: String,
      mergeClauses: MergeClause*)(
      result: Seq[(Int, Int)]): Unit = {
    Seq(true, false).foreach { isPartitioned =>
      test(s"$namePrefix - $name - isPartitioned: $isPartitioned ") {
        withKeyValueData(source, target, isPartitioned) { case (sourceName, targetName) =>
          withSQLConf(DeltaSQLConf.MERGE_INSERT_ONLY_ENABLED.key -> "true") {
            executeMerge(s"$targetName t", s"$sourceName s", mergeOn, mergeClauses: _*)
          }
          val deltaPath = if (targetName.startsWith("delta.`")) {
            targetName.stripPrefix("delta.`").stripSuffix("`")
          } else targetName
          checkAnswer(
            readDeltaTable(deltaPath),
            result.map { case (k, v) => Row(k, v) })
        }
      }
    }
  }

  protected def testExtendedMergeErrorOnMultipleMatches(
      name: String)(
      source: Seq[(Int, Int)],
      target: Seq[(Int, Int)],
      mergeOn: String,
      mergeClauses: MergeClause*): Unit = {
    test(s"extended syntax - $name") {
      withKeyValueData(source, target) { case (sourceName, targetName) =>
        val errMsg = intercept[UnsupportedOperationException] {
          executeMerge(s"$targetName t", s"$sourceName s", mergeOn, mergeClauses: _*)
        }.getMessage.toLowerCase(Locale.ROOT)
        assert(errMsg.contains("cannot perform merge as multiple source rows matched"))
      }
    }
  }

  testExtendedMerge("only update")(
    source = (0, 0) :: (1, 10) :: (3, 30) :: Nil,
    target = (1, 1) :: (2, 2)  :: Nil,
    mergeOn = "s.key = t.key",
    update(set = "key = s.key, value = s.value"))(
    result = Seq(
      (1, 10),  // (1, 1) updated
      (2, 2)
    ))

  testExtendedMergeErrorOnMultipleMatches("only update with multiple matches")(
    source = (0, 0) :: (1, 10) :: (1, 11) :: (2, 20) :: Nil,
    target = (1, 1) :: (2, 2) :: Nil,
    mergeOn = "s.key = t.key",
    update(set = "key = s.key, value = s.value"))

  testExtendedMerge("only conditional update")(
    source = (0, 0) :: (1, 10) :: (2, 20) :: (3, 30) :: Nil,
    target = (1, 1) :: (2, 2) :: (3, 3) :: Nil,
    mergeOn = "s.key = t.key",
    update(condition = "s.value <> 20 AND t.value <> 3", set = "key = s.key, value = s.value"))(
    result = Seq(
      (1, 10),  // updated
      (2, 2),   // not updated due to source-only condition `s.value <> 20`
      (3, 3)    // not updated due to target-only condition `t.value <> 3`
    ))

  testExtendedMergeErrorOnMultipleMatches("only conditional update with multiple matches")(
    source = (0, 0) :: (1, 10) :: (1, 11) :: (2, 20) :: Nil,
    target = (1, 1) :: (2, 2) :: Nil,
    mergeOn = "s.key = t.key",
    update(condition = "s.value = 10", set = "key = s.key, value = s.value"))

  testExtendedMerge("only delete")(
    source = (0, 0) :: (1, 10) :: (3, 30) :: Nil,
    target = (1, 1) :: (2, 2) :: Nil,
    mergeOn = "s.key = t.key",
    delete())(
    result = Seq(
      (2, 2)    // (1, 1) deleted
    ))          // (3, 30) not inserted as not insert clause

  // This is not ambiguous even when there are multiple matches
  testExtendedMerge(s"only delete with multiple matches")(
    source = (0, 0) :: (1, 10) :: (1, 100) :: (3, 30) :: Nil,
    target = (1, 1) :: (2, 2) :: Nil,
    mergeOn = "s.key = t.key",
    delete())(
    result = Seq(
      (2, 2)  // (1, 1) matches multiple source rows but unambiguously deleted
    )
  )

  testExtendedMerge("only conditional delete")(
    source = (0, 0) :: (1, 10) :: (2, 20) :: (3, 30) :: Nil,
    target = (1, 1) :: (2, 2) :: (3, 3) :: Nil,
    mergeOn = "s.key = t.key",
    delete(condition = "s.value <> 20 AND t.value <> 3"))(
    result = Seq(
      (2, 2),   // not deleted due to source-only condition `s.value <> 20`
      (3, 3)    // not deleted due to target-only condition `t.value <> 3`
    ))          // (1, 1) deleted

  testExtendedMergeErrorOnMultipleMatches("only conditional delete with multiple matches")(
    source = (0, 0) :: (1, 10) :: (1, 100) :: (2, 20) :: Nil,
    target = (1, 1) :: (2, 2) :: Nil,
    mergeOn = "s.key = t.key",
    delete(condition = "s.value = 10"))

  testExtendedMerge("conditional update + delete")(
    source = (0, 0) :: (1, 10) :: (2, 20) :: Nil,
    target = (1, 1) :: (2, 2) :: (3, 3) :: Nil,
    mergeOn = "s.key = t.key",
    update(condition = "s.key <> 1", set = "key = s.key, value = s.value"),
    delete())(
    result = Seq(
      (2, 20),  // (2, 2) updated, (1, 1) deleted as it did not match update condition
      (3, 3)
    ))

  testExtendedMergeErrorOnMultipleMatches("conditional update + delete with multiple matches")(
    source = (0, 0) :: (1, 10) :: (2, 20) :: (2, 200) :: Nil,
    target = (1, 1) :: (2, 2) :: Nil,
    mergeOn = "s.key = t.key",
    update(condition = "s.value = 20", set = "key = s.key, value = s.value"),
    delete())

  testExtendedMerge("conditional update + conditional delete")(
    source = (0, 0) :: (1, 10) :: (2, 20) :: (3, 30) :: Nil,
    target = (1, 1) :: (2, 2) :: (3, 3) :: (4, 4) :: Nil,
    mergeOn = "s.key = t.key",
    update(condition = "s.key <> 1", set = "key = s.key, value = s.value"),
    delete(condition = "s.key <> 2"))(
    result = Seq(
      (2, 20),  // (2, 2) updated as it matched update condition
      (3, 30),  // (3, 3) updated even though it matched update and delete conditions, as update 1st
      (4, 4)
    ))          // (1, 1) deleted as it matched delete condition

  testExtendedMergeErrorOnMultipleMatches(
    "conditional update + conditional delete with multiple matches")(
    source = (0, 0) :: (1, 10) :: (1, 100) :: (2, 20) :: (2, 200) :: Nil,
    target = (1, 1) :: (2, 2) :: Nil,
    mergeOn = "s.key = t.key",
    update(condition = "s.value = 20", set = "key = s.key, value = s.value"),
    delete(condition = "s.value = 10"))

  testExtendedMerge("conditional delete + conditional update (order matters)")(
    source = (0, 0) :: (1, 10) :: (2, 20) :: (3, 30) :: Nil,
    target = (1, 1) :: (2, 2) :: (3, 3) :: (4, 4) :: Nil,
    mergeOn = "s.key = t.key",
    delete(condition = "s.key <> 2"),
    update(condition = "s.key <> 1", set = "key = s.key, value = s.value"))(
    result = Seq(
      (2, 20),  // (2, 2) updated as it matched update condition
      (4, 4)    // (4, 4) unchanged
    ))          // (1, 1) and (3, 3) deleted as they matched delete condition (before update cond)

  testExtendedMerge("only insert")(
    source = (0, 0) :: (1, 10) :: (3, 30) :: Nil,
    target = (1, 1) :: (2, 2) :: Nil,
    mergeOn = "s.key = t.key",
    insert(values = "(key, value) VALUES (s.key, s.value)"))(
    result = Seq(
      (0, 0),   // (0, 0) inserted
      (1, 1),   // (1, 1) not updated as no update clause
      (2, 2),   // (2, 2) not updated as no update clause
      (3, 30)   // (3, 30) inserted
    ))

  testExtendedMerge("only conditional insert")(
    source = (0, 0) :: (1, 10) :: (3, 30) :: Nil,
    target = (1, 1) :: (2, 2) :: Nil,
    mergeOn = "s.key = t.key",
    insert(condition = "s.value <> 30", values = "(key, value) VALUES (s.key, s.value)"))(
    result = Seq(
      (0, 0),   // (0, 0) inserted by condition but not (3, 30)
      (1, 1),
      (2, 2)
    ))

  testExtendedMerge("update + conditional insert")(
    source = (0, 0) :: (1, 10) :: (3, 30) :: Nil,
    target = (1, 1) :: (2, 2) :: Nil,
    mergeOn = "s.key = t.key",
    update("key = s.key, value = s.value"),
    insert(condition = "s.value <> 30", values = "(key, value) VALUES (s.key, s.value)"))(
    result = Seq(
      (0, 0),   // (0, 0) inserted by condition but not (3, 30)
      (1, 10),  // (1, 1) updated
      (2, 2)
    ))

  testExtendedMerge("conditional update + conditional insert")(
    source = (0, 0) :: (1, 10) :: (2, 20) :: (3, 30) :: Nil,
    target = (1, 1) :: (2, 2) :: Nil,
    mergeOn = "s.key = t.key",
    update(condition = "s.key > 1", set = "key = s.key, value = s.value"),
    insert(condition = "s.key > 1", values = "(key, value) VALUES (s.key, s.value)"))(
    result = Seq(
      (1, 1),   // (1, 1) not updated by condition
      (2, 20),  // (2, 2) updated by condition
      (3, 30)   // (3, 30) inserted by condition but not (0, 0)
    ))

  // This is specifically to test the MergeIntoDeltaCommand.writeOnlyInserts code paths
  testExtendedMerge("update + conditional insert clause with data to only insert, no updates")(
    source = (0, 0) :: (3, 30) :: Nil,
    target = (1, 1) :: (2, 2) :: Nil,
    mergeOn = "s.key = t.key",
    update("key = s.key, value = s.value"),
    insert(condition = "s.value <> 30", values = "(key, value) VALUES (s.key, s.value)"))(
    result = Seq(
      (0, 0),   // (0, 0) inserted by condition but not (3, 30)
      (1, 1),
      (2, 2)
    ))

  testExtendedMerge(s"delete + insert with multiple matches for both") (
    source = (1, 10) :: (1, 100) :: (3, 30) :: (3, 300) :: Nil,
    target = (1, 1) :: (2, 2) :: Nil,
    mergeOn = "s.key = t.key",
    delete(),
    insert(values = "(key, value) VALUES (s.key, s.value)")) (
    result = Seq(
               // (1, 1) matches multiple source rows but unambiguously deleted
      (2, 2),  // existed previously
      (3, 30), // inserted
      (3, 300) // inserted
    )
  )

  testExtendedMerge("conditional update + conditional delete + conditional insert")(
    source = (0, 0) :: (1, 10) :: (2, 20) :: (3, 30) :: (4, 40) :: Nil,
    target = (1, 1) :: (2, 2) :: (3, 3) :: Nil,
    mergeOn = "s.key = t.key",
    update(condition = "s.key < 2", set = "key = s.key, value = s.value"),
    delete(condition = "s.key < 3"),
    insert(condition = "s.key > 1", values = "(key, value) VALUES (s.key, s.value)"))(
    result = Seq(
      (1, 10),  // (1, 1) updated by condition, but not (2, 2) or (3, 3)
      (3, 3),   // neither updated nor deleted as it matched neither condition
      (4, 40)   // (4, 40) inserted by condition, but not (0, 0)
    ))          // (2, 2) deleted by condition but not (1, 1) or (3, 3)

  testExtendedMergeErrorOnMultipleMatches(
    "conditional update + conditional delete + conditional insert with multiple matches")(
    source = (0, 0) :: (1, 10) :: (1, 100) :: (2, 20) :: (2, 200) :: Nil,
    target = (1, 1) :: (2, 2) :: Nil,
    mergeOn = "s.key = t.key",
    update(condition = "s.value = 20", set = "key = s.key, value = s.value"),
    delete(condition = "s.value = 10"),
    insert(condition = "s.value = 0", values = "(key, value) VALUES (s.key, s.value)"))

  // complex merge condition = has target-only and source-only predicates
  testExtendedMerge(
    "conditional update + conditional delete + conditional insert + complex merge condition ")(
    source = (-1, -10) :: (0, 0) :: (1, 10) :: (2, 20) :: (3, 30) :: (4, 40) :: (5, 50) :: Nil,
    target = (-1, -1) :: (1, 1) :: (2, 2) :: (3, 3) :: (5, 5) :: Nil,
    mergeOn = "s.key = t.key AND t.value > 0 AND s.key < 5",
    update(condition = "s.key < 2", set = "key = s.key, value = s.value"),
    delete(condition = "s.key < 3"),
    insert(condition = "s.key > 1", values = "(key, value) VALUES (s.key, s.value)"))(
    result = Seq(
      (-1, -1), // (-1, -1) not matched with (-1, -10) by target-only condition 't.value > 0', so
                // not updated, But (-1, -10) not inserted as insert condition is 's.key > 1'
                // (0, 0) not matched any target but not inserted as insert condition is 's.key > 1'
      (1, 10),  // (1, 1) matched with (1, 10) and updated as update condition is 's.key < 2'
                // (2, 2) matched with (2, 20) and deleted as delete condition is 's.key < 3'
      (3, 3),   // (3, 3) matched with (3, 30) but neither updated nor deleted as it did not
                // satisfy update or delete condition
      (4, 40),  // (4, 40) not matched any target, so inserted as insert condition is 's.key > 1'
      (5, 5),   // (5, 5) not matched with (5, 50) by source-only condition 's.key < 5', no update
      (5, 50)   // (5, 50) inserted as inserted as insert condition is 's.key > 1'
    ))

  test("extended syntax - different # cols in source than target") {
    val sourceData =
      (0, 0, 0) :: (1, 10, 100) :: (2, 20, 200) :: (3, 30, 300) :: (4, 40, 400) :: Nil
    val targetData = (1, 1) :: (2, 2) :: (3, 3) :: Nil

    withTempView("source") {
      append(targetData.toDF("key", "value"), Nil)
      sourceData.toDF("key", "value", "extra").createOrReplaceTempView("source")
      executeMerge(
        s"delta.`$tempPath` t",
        "source s",
        cond = "s.key = t.key",
        update(condition = "s.key < 2", set = "key = s.key, value = s.value + s.extra"),
        delete(condition = "s.key < 3"),
        insert(condition = "s.key > 1", values = "(key, value) VALUES (s.key, s.value + s.extra)"))

      checkAnswer(
        readDeltaTable(tempPath),
        Seq(
          Row(1, 110),  // (1, 1) updated by condition, but not (2, 2) or (3, 3)
          Row(3, 3),    // neither updated nor deleted as it matched neither condition
          Row(4, 440)   // (4, 40) inserted by condition, but not (0, 0)
        ))              // (2, 2) deleted by condition but not (1, 1) or (3, 3)
    }
  }

  protected def withJsonData(
      source: Seq[String],
      target: Seq[String],
      schema: StructType = null)(
      thunk: (String, String) => Unit): Unit = {

    def toDF(strs: Seq[String]) = {
      if (schema != null) spark.read.schema(schema).json(strs.toDS) else spark.read.json(strs.toDS)
    }
    append(toDF(target), Nil)
    withTempView("source") {
      toDF(source).createOrReplaceTempView("source")
      thunk("source", s"delta.`$tempPath`")
    }
  }

  test("extended syntax - nested data - conditions and actions") {
    withJsonData(
      source =
        """{ "key": { "x": "X1", "y": 1}, "value" : { "a": 100, "b": "B100" } }
          { "key": { "x": "X2", "y": 2}, "value" : { "a": 200, "b": "B200" } }
          { "key": { "x": "X3", "y": 3}, "value" : { "a": 300, "b": "B300" } }
          { "key": { "x": "X4", "y": 4}, "value" : { "a": 400, "b": "B400" } }""",
      target =
        """{ "key": { "x": "X1", "y": 1}, "value" : { "a": 1,   "b": "B1" } }
          { "key": { "x": "X2", "y": 2}, "value" : { "a": 2,   "b": "B2" } }"""
    ) { case (sourceName, targetName) =>
      executeMerge(
        s"$targetName t",
        s"$sourceName s",
        cond = "s.key = t.key",
        update(condition = "s.key.y < 2", set = "key = s.key, value = s.value"),
        insert(condition = "s.key.x < 'X4'", values = "(key, value) VALUES (s.key, s.value)"))

      checkAnswer(
        readDeltaTable(tempPath),
        spark.read.json(Seq(
          """{ "key": { "x": "X1", "y": 1}, "value" : { "a": 100, "b": "B100" } }""", // updated
          """{ "key": { "x": "X2", "y": 2}, "value" : { "a": 2,   "b": "B2"   } }""", // not updated
          """{ "key": { "x": "X3", "y": 3}, "value" : { "a": 300, "b": "B300" } }"""  // inserted
        ).toDS))
    }
  }

  protected implicit def strToJsonSeq(str: String): Seq[String] = {
    str.split("\n").filter(_.trim.length > 0)
  }

  def testStar(
      name: String)(
      source: Seq[String],
      target: Seq[String],
      mergeClauses: MergeClause*)(
      result: Seq[String] = null,
      errorStrs: Seq[String] = null) {

    require(result == null ^ errorStrs == null, "either set the result or the error strings")
    val testName =
      if (result != null) s"star syntax - $name" else s"star syntax - analysis error - $name"

    test(testName) {
      withJsonData(source, target) { case (sourceName, targetName) =>
        def execMerge() =
          executeMerge(s"$targetName t", s"$sourceName s", "s.key = t.key", mergeClauses: _*)
        if (result != null) {
          execMerge()
          val deltaPath = if (targetName.startsWith("delta.`")) {
            targetName.stripPrefix("delta.`").stripSuffix("`")
          } else targetName
          checkAnswer(readDeltaTable(deltaPath), spark.read.json(result.toDS))
        } else {
          val e = intercept[AnalysisException] { execMerge() }
          errorStrs.foreach { s => errorContains(e.getMessage, s) }
        }
      }
    }
  }

  testStar("basic star expansion")(
    source =
      """{ "key": "a", "value" : 10 }
         { "key": "c", "value" : 30 }""",
    target =
      """{ "key": "a", "value" : 1 }
         { "key": "b", "value" : 2 }""",
    update(set = "*"),
    insert(values = "*"))(
    result =
      """{ "key": "a", "value" : 10 }
         { "key": "b", "value" : 2   }
         { "key": "c", "value" : 30 }""")

  testStar("multiples columns and extra columns in source")(
    source =
      """{ "key": "a", "value" : 10, "value2" : 100, "value3" : 1000 }
         { "key": "c", "value" : 30, "value2" : 300, "value3" : 3000 }""",
    target =
      """{ "key": "a", "value" : 1, "value2" : 1 }
         { "key": "b", "value" : 2, "value2" : 2 }""",
    update(set = "*"),
    insert(values = "*"))(
    result =
      """{ "key": "a", "value" : 10, "value2" : 100 }
         { "key": "b", "value" : 2,  "value2" : 2  }
         { "key": "c", "value" : 30, "value2" : 300 }""")

  testExtendedMerge("insert only merge")(
    source = (0, 0) :: (1, 10) :: (3, 30) :: Nil,
    target = (1, 1) :: (2, 2)  :: Nil,
    mergeOn = "s.key = t.key",
    insert(values = "*"))(
    result = Seq(
      (0, 0), // inserted
      (1, 1), // existed previously
      (2, 2), // existed previously
      (3, 30) // inserted
    ))

  testExtendedMerge("insert only merge with insert condition on source")(
    source = (0, 0) :: (1, 10) :: (3, 30) :: Nil,
    target = (1, 1) :: (2, 2)  :: Nil,
    mergeOn = "s.key = t.key",
    insert(values = "*", condition = "s.key = s.value"))(
    result = Seq(
      (0, 0), // inserted
      (1, 1), // existed previously
      (2, 2)  // existed previously
    ))

  testExtendedMerge("insert only merge with predicate insert")(
    source = (0, 0) :: (1, 10) :: (3, 30) :: Nil,
    target = (1, 1) :: (2, 2)  :: Nil,
    mergeOn = "s.key = t.key",
    insert(values = "(t.key, t.value) VALUES (s.key + 10, s.value + 10)"))(
    result = Seq(
      (10, 10), // inserted
      (1, 1), // existed previously
      (2, 2), // existed previously
      (13, 40) // inserted
    ))

  testExtendedMerge(s"insert only merge with multiple matches") (
    source = (0, 0) :: (1, 10) :: (1, 100) :: (3, 30) :: (3, 300) :: Nil,
    target = (1, 1) :: (2, 2) :: Nil,
    mergeOn = "s.key = t.key",
    insert(values = "(key, value) VALUES (s.key, s.value)")) (
    result = Seq(
      (0, 0), // inserted
      (1, 1), // existed previously
      (2, 2), // existed previously
      (3, 30), // inserted
      (3, 300) // key exists but still inserted
    )
  )


  protected def testNullCaseInsertOnly(name: String)(
    target: Seq[(JInt, JInt)],
    source: Seq[(JInt, JInt)],
    condition: String,
    expectedResults: Seq[(JInt, JInt)],
    insertCondition: Option[String] = None) = {
    Seq(true, false).foreach { isPartitioned =>
      test(s"basic case - null handling - $name, isPartitioned: $isPartitioned") {
        withView("sourceView") {
          val partitions = if (isPartitioned) "key" :: Nil else Nil
          append(target.toDF("key", "value"), partitions)
          source.toDF("key", "value").createOrReplaceTempView("sourceView")
          withSQLConf(DeltaSQLConf.MERGE_INSERT_ONLY_ENABLED.key -> "true") {
            if (insertCondition.isDefined) {
              executeMerge(
                s"delta.`$tempPath` as t",
                "sourceView s",
                condition,
                insert("(t.key, t.value) VALUES (s.key, s.value)",
                  condition = insertCondition.get))
            } else {
              executeMerge(
                s"delta.`$tempPath` as t",
                "sourceView s",
                condition,
                insert("(t.key, t.value) VALUES (s.key, s.value)"))
            }
          }
          checkAnswer(
            readDeltaTable(tempPath),
            expectedResults.map { r => Row(r._1, r._2) }
          )

          Utils.deleteRecursively(new File(tempPath))
        }
      }
    }
  }

  testNullCaseInsertOnly("insert only merge - null in source") (
    target = Seq((1, 1)),
    source = Seq((1, 10), (2, 20), (null, null)),
    condition = "s.key = t.key",
    expectedResults = Seq(
      (1, 1),         // Existing value
      (2, 20),        // Insert
      (null, null)    // Insert
    ))

  testNullCaseInsertOnly("insert only merge - null value in both source and target")(
    target = Seq((1, 1), (null, null)),
    source = Seq((1, 10), (2, 20), (null, 0)),
    condition = "s.key = t.key",
    expectedResults = Seq(
      (null, null),   // No change as null in source does not match null in target
      (1, 1),         // Existing value
      (2, 20),        // Insert
      (null, 0)       // Insert
    ))

  testNullCaseInsertOnly("insert only merge - null in insert clause")(
    target = Seq((1, 1), (2, 20)),
    source = Seq((1, 10), (3, 30), (null, 0)),
    condition = "s.key = t.key",
    expectedResults = Seq(
      (1, 1),         // Existing value
      (2, 20),        // Existing value
      (null, 0)       // Insert
    ),
    insertCondition = Some("s.key IS NULL")
  )

  test("insert only merge - turn off feature flag") {
    withSQLConf(DeltaSQLConf.MERGE_INSERT_ONLY_ENABLED.key -> "false") {
      withKeyValueData(
        source = (1, 10) :: (3, 30) :: Nil,
        target = (1, 1) :: Nil
      ) { case (sourceName, targetName) =>
        executeMerge(
          s"$targetName t",
          s"$sourceName s",
          "s.key = t.key",
          insert(values = "(key, value) VALUES (s.key, s.value)"))

        checkAnswer(sql(s"SELECT key, value FROM $targetName"),
          Row(1, 1) :: Row(3, 30) :: Nil)

        val metrics = spark.sql(s"DESCRIBE HISTORY $targetName LIMIT 1")
          .select("operationMetrics")
          .collect().head.getMap(0).asInstanceOf[Map[String, String]]
        assert(metrics.contains("numTargetFilesRemoved"))
        // If insert-only code path is not used, then the general code path will rewrite existing
        // target files.
        assert(metrics("numTargetFilesRemoved").toInt > 0)
      }
    }
  }

  test("insert only merge - multiple matches when feature flag off") {
    withSQLConf(DeltaSQLConf.MERGE_INSERT_ONLY_ENABLED.key -> "false") {
      // Verify that in case of multiple matches, it throws error rather than producing
      // incorrect results.
      withKeyValueData(
        source = (1, 10) :: (1, 100) :: (2, 20) :: Nil,
        target = (1, 1) :: Nil
      ) { case (sourceName, targetName) =>
        val errMsg = intercept[UnsupportedOperationException] {
          executeMerge(
            s"$targetName t",
            s"$sourceName s",
            "s.key = t.key",
            insert(values = "(key, value) VALUES (s.key, s.value)"))
        }.getMessage.toLowerCase(Locale.ROOT)
        assert(errMsg.contains("cannot perform merge as multiple source rows matched"))
      }

      // Verify that in case of multiple matches, it throws error rather than producing
      // incorrect results.
      withKeyValueData(
        source = (1, 10) :: (1, 100) :: (2, 20) :: (2, 200) :: Nil,
        target = (1, 1) :: Nil
      ) { case (sourceName, targetName) =>
        val errMsg = intercept[UnsupportedOperationException] {
          executeMerge(
            s"$targetName t",
            s"$sourceName s",
            "s.key = t.key",
            insert(condition = "s.value = 20", values = "(key, value) VALUES (s.key, s.value)"))
        }.getMessage.toLowerCase(Locale.ROOT)
        assert(errMsg.contains("cannot perform merge as multiple source rows matched"))
      }
    }
  }

  def testMergeWithRepartition(
      name: String,
      partitionColumns: Seq[String],
      srcRange: Range,
      expectLessFilesWithRepartition: Boolean,
      clauses: MergeClause*): Unit = {
    test(s"merge with repartition - $name",
      DisableAdaptiveExecution("AQE coalese would partition number")) {
      withTempView("source") {
        withTempDir { basePath =>
          val tgt1 = basePath + "target"
          val tgt2 = basePath + "targetRepartitioned"

          val df = spark.range(100).withColumn("part1", 'id % 5).withColumn("part2", 'id % 3)
          df.write.format("delta").partitionBy(partitionColumns: _*).save(tgt1)
          df.write.format("delta").partitionBy(partitionColumns: _*).save(tgt2)
          val cond = "src.id = t.id"
          val src = srcRange.toDF("id")
            .withColumn("part1", 'id % 5)
            .withColumn("part2", 'id % 3)
            .createOrReplaceTempView("source")
          // execute merge without repartition
          executeMerge(
            tgt = s"delta.`$tgt1` as t",
            src = "source src",
            cond = cond,
            clauses = clauses: _*)

          // execute merge with repartition
          withSQLConf(DeltaSQLConf.MERGE_REPARTITION_BEFORE_WRITE.key -> "true") {
            executeMerge(
              tgt = s"delta.`$tgt2` as t",
              src = "source src",
              cond = cond,
              clauses = clauses: _*)
          }
          checkAnswer(
            io.delta.tables.DeltaTable.forPath(tgt2).toDF,
            io.delta.tables.DeltaTable.forPath(tgt1).toDF
          )
          val filesAfterNoRepartition = DeltaLog.forTable(spark, tgt1).snapshot.numOfFiles
          val filesAfterRepartition = DeltaLog.forTable(spark, tgt2).snapshot.numOfFiles
          // check if there are fewer are number of files for merge with repartition
          if (expectLessFilesWithRepartition) {
            assert(filesAfterNoRepartition > filesAfterRepartition)
          } else {
            assert(filesAfterNoRepartition === filesAfterRepartition)
          }
        }
      }
    }
  }

  testMergeWithRepartition(
    name = "partition on multiple columns",
    partitionColumns = Seq("part1", "part2"),
    srcRange = Range(80, 110),
    expectLessFilesWithRepartition = true,
    update("t.part2 = 1"),
    insert("(id, part1, part2) VALUES (id, part1, part2)")
  )

  testMergeWithRepartition(
    name = "insert only merge",
    partitionColumns = Seq("part1"),
    srcRange = Range(110, 150),
    expectLessFilesWithRepartition = true,
    insert("(id, part1, part2) VALUES (id, part1, part2)")
  )

  testMergeWithRepartition(
    name = "non partitioned table",
    partitionColumns = Seq(),
    srcRange = Range(80, 180),
    expectLessFilesWithRepartition = false,
    update("t.part2 = 1"),
    insert("(id, part1, part2) VALUES (id, part1, part2)")
  )

  protected def testMatchedOnlyOptimization(
      name: String)(
      source: Seq[(Int, Int)],
      target: Seq[(Int, Int)],
      mergeOn: String,
      mergeClauses: MergeClause*) (
      result: Seq[(Int, Int)]): Unit = {
    Seq(true, false).foreach { matchedOnlyEnabled =>
      Seq(true, false).foreach { isPartitioned =>
        val s = if (matchedOnlyEnabled) "enabled" else "disabled"
        test(s"matched only merge - $s - $name - isPartitioned: $isPartitioned ") {
          withKeyValueData(source, target, isPartitioned) { case (sourceName, targetName) =>
            withSQLConf(DeltaSQLConf.MERGE_MATCHED_ONLY_ENABLED.key -> s"$matchedOnlyEnabled") {
              executeMerge(s"$targetName t", s"$sourceName s", mergeOn, mergeClauses: _*)
            }
            val deltaPath = if (targetName.startsWith("delta.`")) {
              targetName.stripPrefix("delta.`").stripSuffix("`")
            } else targetName
            checkAnswer(
              readDeltaTable(deltaPath),
              result.map { case (k, v) => Row(k, v) })
          }
        }
      }
    }
  }

  testMatchedOnlyOptimization("with update") (
    source = Seq((1, 100), (3, 300), (5, 500)),
    target = Seq((1, 10), (2, 20), (3, 30)),
    mergeOn = "s.key = t.key",
    update("t.key = s.key, t.value = s.value")) (
    result = Seq(
      (1, 100), // updated
      (2, 20), // existed previously
      (3, 300) // updated
    )
  )

  testMatchedOnlyOptimization("with delete") (
    source = Seq((1, 100), (3, 300), (5, 500)),
    target = Seq((1, 10), (2, 20), (3, 30)),
    mergeOn = "s.key = t.key",
    delete()) (
    result = Seq(
      (2, 20) // existed previously
    )
  )

  testMatchedOnlyOptimization("with update and delete")(
    source = Seq((1, 100), (3, 300), (5, 500)),
    target = Seq((1, 10), (3, 30), (5, 30)),
    mergeOn = "s.key = t.key",
    update("t.value = s.value", "t.key < 3"), delete("t.key > 3")) (
    result = Seq(
      (1, 100), // updated
      (3, 30)   // existed previously
    )
  )

  protected def testNullCaseMatchedOnly(name: String) (
      source: Seq[(JInt, JInt)],
      target: Seq[(JInt, JInt)],
      mergeOn: String,
      result: Seq[(JInt, JInt)]) = {
    Seq(true, false).foreach { isPartitioned =>
      withSQLConf(DeltaSQLConf.MERGE_MATCHED_ONLY_ENABLED.key -> "true") {
        test(s"matched only merge - null handling - $name, isPartitioned: $isPartitioned") {
          withView("sourceView") {
            val partitions = if (isPartitioned) "key" :: Nil else Nil
            append(target.toDF("key", "value"), partitions)
            source.toDF("key", "value").createOrReplaceTempView("sourceView")

            executeMerge(
              tgt = s"delta.`$tempPath` as t",
              src = "sourceView s",
              cond = mergeOn,
              update("t.value = s.value"))

            checkAnswer(
              readDeltaTable(tempPath),
              result.map { r => Row(r._1, r._2) }
            )

            Utils.deleteRecursively(new File(tempPath))
          }
        }
      }
    }
  }

  testNullCaseMatchedOnly("null in source") (
    source = Seq((1, 10), (2, 20), (null, null)),
    target = Seq((1, 1)),
    mergeOn = "s.key = t.key",
    result = Seq(
      (1, 10) // update
    )
  )

  testNullCaseMatchedOnly("null value in both source and target") (
    source = Seq((1, 10), (2, 20), (null, 0)),
    target = Seq((1, 1), (null, null)),
    mergeOn = "s.key = t.key",
    result = Seq(
      (null, null), // No change as null in source does not match null in target
      (1, 10) // update
    )
  )

  protected def testEvolution(name: String)(
      targetData: => DataFrame,
      sourceData: => DataFrame,
      update: String = null,
      insert: String = null,
      expected: Seq[Product] = null,
      expectedWithoutEvolution: Seq[Product] = null,
      expectErrorContains: String = null,
      expectErrorWithoutEvolutionContains: String = null) = {
    test(s"schema evolution - $name - with evolution disabled") {
      append(targetData)
      withTempView("source") {
        sourceData.createOrReplaceTempView("source")
        val clauses = Option(update).map(u => this.update(set = u)) ++
          Option(insert).map(i => this.insert(values = i))

        if (expectErrorWithoutEvolutionContains != null) {
          val ex = intercept[AnalysisException] {
            executeMerge(s"delta.`$tempPath` t", s"source s", "s.key = t.key",
              clauses.toSeq: _*)
          }
          assert(ex.getMessage.contains(expectErrorWithoutEvolutionContains))
        } else {
          executeMerge(s"delta.`$tempPath` t", s"source s", "s.key = t.key",
            clauses.toSeq: _*)
          checkAnswer(
            spark.read.format("delta").load(tempPath),
            expectedWithoutEvolution.map(Row.fromTuple))
        }
      }
    }

    test(s"schema evolution - $name") {
      withSQLConf((DeltaSQLConf.DELTA_SCHEMA_AUTO_MIGRATE.key, "true")) {
        append(targetData)
        withTempView("source") {
          sourceData.createOrReplaceTempView("source")
          val clauses = Option(update).map(u => this.update(set = u)) ++
            Option(insert).map(i => this.insert(values = i))

          if (expectErrorContains != null) {
            val ex = intercept[AnalysisException] {
              executeMerge(s"delta.`$tempPath` t", s"source s", "s.key = t.key",
                clauses.toSeq: _*)
            }
            assert(ex.getMessage.contains(expectErrorContains))
          } else {
            executeMerge(s"delta.`$tempPath` t", s"source s", "s.key = t.key",
              clauses.toSeq: _*)
            checkAnswer(
              spark.read.format("delta").load(tempPath),
              expected.map(Row.fromTuple))
          }
        }
      }
    }
  }

  testEvolution("new column with only insert *")(
    targetData = Seq((0, 0), (1, 10), (3, 30)).toDF("key", "value"),
    sourceData = Seq((1, 1, "extra1"), (2, 2, "extra2")).toDF("key", "value", "extra"),
    insert = "*",
    expected =
      (0, 0, null) +: (3, 30, null) +: // unchanged
        (1, 10, null) +:  // not updated
        (2, 2, "extra2") +: Nil, // newly inserted,
    expectedWithoutEvolution =
      (0, 0) +: (3, 30) +: (1, 10) +: (2, 2) +: Nil
  )

  testEvolution("new column with only update *")(
    targetData = Seq((0, 0), (1, 10), (3, 30)).toDF("key", "value"),
    sourceData = Seq((1, 1, "extra1"), (2, 2, "extra2")).toDF("key", "value", "extra"),
    update = "*",
    expected =
      (0, 0, null) +: (3, 30, null) +:
        (1, 1, "extra1") +: // updated
        Nil, // row 2 not inserted
    expectedWithoutEvolution = (0, 0) +: (3, 30) +: (1, 1) +: Nil
  )

  testEvolution("update * with column not in source")(
    targetData = Seq((0, 0, 0), (1, 10, 10), (3, 30, 30)).toDF("key", "value", "extra"),
    sourceData = Seq((1, 1), (2, 2)).toDF("key", "value"),
    update = "*",
    // update went through even though `extra` wasn't there
    expected = (0, 0, 0) +: (1, 1, 10) +: (3, 30, 30) +: Nil,
    expectErrorWithoutEvolutionContains = "cannot resolve `extra` in UPDATE clause"
  )

  testEvolution("insert * with column not in source")(
    targetData = Seq((0, 0, 0), (1, 10, 10), (3, 30, 30)).toDF("key", "value", "extra"),
    sourceData = Seq((1, 1), (2, 2)).toDF("key", "value"),
    insert = "*",
    // insert went through even though `extra` wasn't there
    expected = (0, 0, 0) +: (1, 10, 10) +: (2, 2, null) +: (3, 30, 30) +: Nil,
    expectErrorWithoutEvolutionContains = "cannot resolve `extra` in INSERT clause"
  )

  testEvolution("explicitly insert subset of columns")(
    targetData = Seq((0, 0, 0), (1, 10, 10), (3, 30, 30)).toDF("key", "value", "extra"),
    sourceData = Seq((1, 1, 1), (2, 2, 2)).toDF("key", "value", "extra"),
    insert = "(key, value) VALUES (s.key, s.value)",
    // 2 should have extra = null, since extra wasn't in the insert spec.
    expected = (0, 0, 0) +: (1, 10, 10) +: (2, 2, null) +: (3, 30, 30) +: Nil,
    expectedWithoutEvolution = (0, 0, 0) +: (1, 10, 10) +: (2, 2, null) +: (3, 30, 30) +: Nil
  )

  testEvolution("new column with update non-* and insert *")(
    targetData = Seq((0, 0), (1, 10), (3, 30)).toDF("key", "value"),
    sourceData = Seq((1, 1, 1), (2, 2, 2)).toDF("key", "value", "extra"),
    update = "key = s.key, value = s.value",
    insert = "*",
    expected = (0, 0, null) +: (2, 2, 2) +: (3, 30, null) +:
      // null because `extra` isn't an update action, even though it's 1 in the source data
      (1, 1, null) +: Nil,
    expectedWithoutEvolution = (0, 0) +: (2, 2) +: (3, 30) +: (1, 1) +: Nil
  )

  testEvolution("new column with update * and insert non-*")(
    targetData = Seq((0, 0), (1, 10), (3, 30)).toDF("key", "value"),
    sourceData = Seq((1, 1, 1), (2, 2, 2)).toDF("key", "value", "extra"),
    update = "*",
    insert = "(key, value) VALUES (s.key, s.value)",
    expected = (0, 0, null) +: (1, 1, 1) +: (3, 30, null) +:
      // null because `extra` isn't an insert action, even though it's 2 in the source data
      (2, 2, null) +: Nil,
    expectedWithoutEvolution = (0, 0) +: (2, 2) +: (3, 30) +: (1, 1) +: Nil
  )

  testEvolution("evolve partitioned table")(
    targetData = Seq((0, 0), (1, 10), (3, 30)).toDF("key", "value"),
    sourceData = Seq((1, 1, "extra1"), (2, 2, "extra2")).toDF("key", "value", "extra"),
    update = "*",
    insert = "*",
    expected = (0, 0, null) +: (1, 1, "extra1") +: (2, 2, "extra2") +: (3, 30, null) +: Nil,
    expectedWithoutEvolution = (0, 0) +: (2, 2) +: (3, 30) +: (1, 1) +: Nil
  )

  testEvolution("star expansion with names including dots")(
    targetData = Seq((0, 0), (1, 10), (3, 30)).toDF("key", "value.with.dotted.name"),
    sourceData = Seq((1, 1, "extra1"), (2, 2, "extra2")).toDF(
      "key", "value.with.dotted.name", "extra.dotted"),
    update = "*",
    insert = "*",
    expected = (0, 0, null) +: (1, 1, "extra1") +: (2, 2, "extra2") +: (3, 30, null) +: Nil,
    expectedWithoutEvolution = (0, 0) +: (2, 2) +: (3, 30) +: (1, 1) +: Nil
  )

  // Note that incompatible types are those where a cast to the target type can't resolve - any
  // valid cast will be permitted.
  testEvolution("incompatible types in update *")(
    targetData = Seq((0, 0), (1, 10), (3, 30)).toDF("key", "value"),
    sourceData = Seq((1, Array[Byte](1)), (2, Array[Byte](2))).toDF("key", "value"),
    update = "*",
    expectErrorContains = "cannot cast binary to int",
    expectErrorWithoutEvolutionContains = "cannot cast binary to int"
  )

  testEvolution("incompatible types in insert *")(
    targetData = Seq((0, 0), (1, 10), (3, 30)).toDF("key", "value"),
    sourceData = Seq((1, Array[Byte](1)), (2, Array[Byte](2))).toDF("key", "value"),
    insert = "*",
    expectErrorContains = "cannot cast binary to int",
    expectErrorWithoutEvolutionContains = "cannot cast binary to int"
  )

  // All integral types other than long can be upcasted to integer.
  testEvolution("upcast numeric source types into integer target")(
    targetData = Seq((0, 0), (1, 10), (3, 30)).toDF("key", "value"),
    sourceData = Seq((1.toByte, 1.toShort), (2.toByte, 2.toShort)).toDF("key", "value"),
    insert = "*",
    update = "*",
    expected = (0, 0) +: (1, 1) +: (2, 2) +: (3, 30) +: Nil,
    expectedWithoutEvolution = (0, 0) +: (1, 1) +: (2, 2) +: (3, 30) +: Nil
  )

  // Delta's automatic schema evolution allows converting table columns with a numeric type narrower
  // than integer to integer, because in the underlying Parquet they're all stored as ints.
  testEvolution("upcast numeric target types from integer source")(
    targetData = Seq((0.toByte, 0.toShort), (1.toByte, 10.toShort)).toDF("key", "value"),
    sourceData = Seq((1, 1), (2, 2)).toDF("key", "value"),
    insert = "*",
    update = "*",
    expected = (0, 0) +: (1, 1) +: (2, 2) +: Nil,
    expectedWithoutEvolution = (0, 0) +: (1, 1) +: (2, 2) +: Nil
  )

  testEvolution("upcast int source type into long target")(
    targetData = Seq((0, 0L), (1, 10L), (3, 30L)).toDF("key", "value"),
    sourceData = Seq((1, 1), (2, 2)).toDF("key", "value"),
    insert = "*",
    update = "*",
    expected = (0, 0L) +: (1, 1L) +: (2, 2L) +: (3, 30L) +: Nil,
    expectedWithoutEvolution = (0, 0L) +: (1, 1L) +: (2, 2L) +: (3, 30L) +: Nil
  )

  testEvolution("write string into int column")(
    targetData = Seq((0, 0), (1, 10), (3, 30)).toDF("key", "value"),
    sourceData = Seq((1, "1"), (2, "2"), (5, "notANumber")).toDF("key", "value"),
    insert = "*",
    expected = (0, 0) +: (1, 10) +: (2, 2) +: (3, 30) +: (5, null) +: Nil,
    expectedWithoutEvolution = (0, 0) +: (1, 10) +: (2, 2) +: (3, 30) +: (5, null) +: Nil
  )

  // This is kinda bug-for-bug compatibility. It doesn't really make sense that infinity is casted
  // to int as Int.MaxValue, but that's the behavior.
  testEvolution("write double into int column")(
    targetData = Seq((0, 0), (1, 10), (3, 30)).toDF("key", "value"),
    sourceData = Seq((1, 1.1), (2, 2.2), (5, Double.PositiveInfinity)).toDF("key", "value"),
    insert = "*",
    expected = (0, 0) +: (1, 10) +: (2, 2) +: (3, 30) +: (5, Int.MaxValue) +: Nil,
    expectedWithoutEvolution = (0, 0) +: (1, 10) +: (2, 2) +: (3, 30) +: (5, Int.MaxValue) +: Nil
  )

  testEvolution("extra nested column in source")(
    targetData = Seq((1, ("a" -> 1, "b" -> 2))).toDF("key", "x"),
    sourceData = Seq((2, ("a" -> 2, "b" -> 2, "c" -> 3))).toDF("key", "x"),
    insert = "*",
    expectErrorContains = "cannot cast struct",
    expectErrorWithoutEvolutionContains = "cannot cast struct"
  )

  /* unlimited number of merge clauses tests */

  protected def testUnlimitedClauses(
      name: String)(
      source: Seq[(Int, Int)],
      target: Seq[(Int, Int)],
      mergeOn: String,
      mergeClauses: MergeClause*)(
      result: Seq[(Int, Int)]): Unit =
    testExtendedMerge(name, "unlimited clauses")(source, target, mergeOn, mergeClauses : _*)(result)

  protected def testAnalysisErrorsInUnlimitedClauses(
      name: String)(
      mergeOn: String,
      mergeClauses: MergeClause*)(
      errorStrs: Seq[String],
      notErrorStrs: Seq[String] = Nil): Unit =
    testAnalysisErrorsInExtendedMerge(name, "unlimited clauses")(mergeOn, mergeClauses : _*)(
      errorStrs, notErrorStrs)

  testUnlimitedClauses("two conditional update + two conditional delete + insert")(
    source = (0, 0) :: (1, 100) :: (3, 300) :: (4, 400) :: (5, 500) :: Nil,
    target = (1, 10) :: (2, 20) :: (3, 30) :: (4, 40) :: Nil,
    mergeOn = "s.key = t.key",
    delete(condition = "s.key < 2"),
    delete(condition = "s.key > 4"),
    update(condition = "s.key == 3", set = "key = s.key, value = s.value"),
    update(condition = "s.key == 4", set = "key = s.key, value = 2 * s.value"),
    insert(condition = null, values = "(key, value) VALUES (s.key, s.value)"))(
    result = Seq(
      (0, 0),    // insert (0, 0)
                 // delete (1, 10)
      (2, 20),   // neither updated nor deleted as it didn't match
      (3, 300),  // update (3, 30)
      (4, 800),  // update (4, 40)
      (5, 500)   // insert (5, 500)
    ))

  testUnlimitedClauses("two conditional delete + conditional update + update + insert")(
    source = (0, 0) :: (1, 100) :: (2, 200) :: (3, 300) :: (4, 400) :: Nil,
    target = (1, 10) :: (2, 20) :: (3, 30) :: (4, 40) :: Nil,
    mergeOn = "s.key = t.key",
    delete(condition = "s.key < 2"),
    delete(condition = "s.key > 3"),
    update(condition = "s.key == 2", set = "key = s.key, value = s.value"),
    update(condition = null, set = "key = s.key, value = 2 * s.value"),
    insert(condition = null, values = "(key, value) VALUES (s.key, s.value)"))(
    result = Seq(
      (0, 0),   // insert (0, 0)
                // delete (1, 10)
      (2, 200), // update (2, 20)
      (3, 600)  // update (3, 30)
                // delete (4, 40)
    ))

  testUnlimitedClauses("conditional delete + two conditional update + two conditional insert")(
    source = (1, 100) :: (2, 200) :: (3, 300) :: (4, 400) :: (6, 600) :: Nil,
    target = (1, 10) :: (2, 20) :: (3, 30) :: Nil,
    mergeOn = "s.key = t.key",
    delete(condition = "s.key < 2"),
    update(condition = "s.key == 2", set = "key = s.key, value = s.value"),
    update(condition = "s.key == 3", set = "key = s.key, value = 2 * s.value"),
    insert(condition = "s.key < 5", values = "(key, value) VALUES (s.key, s.value)"),
    insert(condition = "s.key > 5", values = "(key, value) VALUES (s.key, 1 + s.value)"))(
    result = Seq(
                // delete (1, 10)
      (2, 200), // update (2, 20)
      (3, 600), // update (3, 30)
      (4, 400), // insert (4, 400)
      (6, 601)  // insert (6, 600)
    ))

  testUnlimitedClauses("conditional update + update + conditional delete + conditional insert")(
    source = (1, 100) :: (2, 200) :: (3, 300) :: (4, 400) :: (5, 500) :: Nil,
    target = (0, 0) :: (1, 10) :: (2, 20) :: (3, 30) :: Nil,
    mergeOn = "s.key = t.key",
    update(condition = "s.key < 2", set = "key = s.key, value = s.value"),
    update(condition = "s.key < 3", set = "key = s.key, value = 2 * s.value"),
    delete(condition = "s.key < 4"),
    insert(condition = "s.key > 4", values = "(key, value) VALUES (s.key, s.value)"))(
    result = Seq(
      (0, 0),   // no change
      (1, 100), // (1, 10) updated by matched_0
      (2, 400), // (2, 20) updated by matched_1
                // (3, 30) deleted by matched_2
      (5, 500)  // (5, 500) inserted
    ))

  testUnlimitedClauses("conditional insert + insert")(
    source = (1, 100) :: (2, 200) :: (3, 300) :: (4, 400) :: (5, 500) :: Nil,
    target = (0, 0) :: (1, 10) :: (2, 20) :: (3, 30) :: Nil,
    mergeOn = "s.key = t.key",
    insert(condition = "s.key < 5", values = "(key, value) VALUES (s.key, s.value)"),
    insert(condition = null, values = "(key, value) VALUES (s.key, s.value + 1)"))(
    result = Seq(
      (0, 0),   // no change
      (1, 10),  // no change
      (2, 20),  // no change
      (3, 30),  // no change
      (4, 400), // (4, 400) inserted by notMatched_0
      (5, 501)  // (5, 501) inserted by notMatched_1
    ))

  testUnlimitedClauses("2 conditional inserts")(
    source = (1, 100) :: (2, 200) :: (3, 300) :: (4, 400) :: (5, 500) :: (6, 600) :: Nil,
    target = (0, 0) :: (1, 10) :: (2, 20) :: (3, 30) :: Nil,
    mergeOn = "s.key = t.key",
    insert(condition = "s.key < 5", values = "(key, value) VALUES (s.key, s.value)"),
    insert(condition = "s.key = 5", values = "(key, value) VALUES (s.key, s.value + 1)"))(
    result = Seq(
      (0, 0),   // no change
      (1, 10),  // no change
      (2, 20),  // no change
      (3, 30),  // no change
      (4, 400), // (4, 400) inserted by notMatched_0
      (5, 501)  // (5, 501) inserted by notMatched_1
                // (6, 600) not inserted as not insert condition matched
    ))

  testUnlimitedClauses("update/delete (no matches) + conditional insert + insert")(
    source = (4, 400) :: (5, 500) :: Nil,
    target = (0, 0) :: (1, 10) :: (2, 20) :: (3, 30) :: Nil,
    mergeOn = "s.key = t.key",
    update(condition = "t.key = 0", set = "key = s.key, value = s.value"),
    delete(condition = null),
    insert(condition = "s.key < 5", values = "(key, value) VALUES (s.key, s.value)"),
    insert(condition = null, values = "(key, value) VALUES (s.key, s.value + 1)"))(
    result = Seq(
      (0, 0),   // no change
      (1, 10),  // no change
      (2, 20),  // no change
      (3, 30),  // no change
      (4, 400), // (4, 400) inserted by notMatched_0
      (5, 501)  // (5, 501) inserted by notMatched_1
    ))

  testUnlimitedClauses("update/delete (no matches) + 2 conditional inserts")(
    source = (4, 400) :: (5, 500) :: (6, 600)  :: Nil,
    target = (0, 0) :: (1, 10) :: (2, 20) :: (3, 30) :: Nil,
    mergeOn = "s.key = t.key",
    update(condition = "t.key = 0", set = "key = s.key, value = s.value"),
    delete(condition = null),
    insert(condition = "s.key < 5", values = "(key, value) VALUES (s.key, s.value)"),
    insert(condition = "s.key = 5", values = "(key, value) VALUES (s.key, s.value + 1)"))(
    result = Seq(
      (0, 0),   // no change
      (1, 10),  // no change
      (2, 20),  // no change
      (3, 30),  // no change
      (4, 400), // (4, 400) inserted by notMatched_0
      (5, 501)  // (5, 501) inserted by notMatched_1
                // (6, 600) not inserted as not insert condition matched
    ))

  testUnlimitedClauses("2 update + 2 delete + 4 insert")(
    source = (1, 100) :: (2, 200) :: (3, 300) :: (4, 400) :: (5, 500) :: (6, 600) :: (7, 700) ::
      (8, 800) :: (9, 900) :: Nil,
    target = (0, 0) :: (1, 10) :: (2, 20) :: (3, 30) :: (4, 40) :: Nil,
    mergeOn = "s.key = t.key",
    update(condition = "s.key == 1", set = "key = s.key, value = s.value"),
    delete(condition = "s.key == 2"),
    update(condition = "s.key == 3", set = "key = s.key, value = 2 * s.value"),
    delete(condition = null),
    insert(condition = "s.key == 5", values = "(key, value) VALUES (s.key, s.value)"),
    insert(condition = "s.key == 6", values = "(key, value) VALUES (s.key, 1 + s.value)"),
    insert(condition = "s.key == 7", values = "(key, value) VALUES (s.key, 2 + s.value)"),
    insert(condition = null, values = "(key, value) VALUES (s.key, 3 + s.value)"))(
    result = Seq(
      (0, 0),    // no change
      (1, 100),  // (1, 10) updated by matched_0
                 // (2, 20) deleted by matched_1
      (3, 600),  // (3, 30) updated by matched_2
                 // (4, 40) deleted by matched_3
      (5, 500),  // (5, 500) inserted by notMatched_0
      (6, 601),  // (6, 600) inserted by notMatched_1
      (7, 702),  // (7, 700) inserted by notMatched_2
      (8, 803),  // (8, 800) inserted by notMatched_3
      (9, 903)   // (9, 900) inserted by notMatched_3
    ))

  testAnalysisErrorsInUnlimitedClauses("error on multiple insert clauses without condition")(
    mergeOn = "s.key = t.key",
    update(condition = "s.key == 3", set = "key = s.key, value = 2 * srcValue"),
    insert(condition = null, values = "(key, value) VALUES (s.key, srcValue)"),
    insert(condition = null, values = "(key, value) VALUES (s.key, 1 + srcValue)"))(
    errorStrs = "when there are more than one not matched clauses in a merge statement, " +
      "only the last not matched clause can omit the condition" :: Nil)

  testAnalysisErrorsInUnlimitedClauses("error on multiple update clauses without condition")(
    mergeOn = "s.key = t.key",
    update(condition = "s.key == 3", set = "key = s.key, value = 2 * srcValue"),
    update(condition = null, set = "key = s.key, value = 3 * srcValue"),
    update(condition = null, set = "key = s.key, value = 4 * srcValue"),
    insert(condition = null, values = "(key, value) VALUES (s.key, srcValue)"))(
    errorStrs = "when there are more than one matched clauses in a merge statement, " +
      "only the last matched clause can omit the condition" :: Nil)

  testAnalysisErrorsInUnlimitedClauses("error on multiple update/delete clauses without condition")(
    mergeOn = "s.key = t.key",
    update(condition = "s.key == 3", set = "key = s.key, value = 2 * srcValue"),
    delete(condition = null),
    update(condition = null, set = "key = s.key, value = 4 * srcValue"),
    insert(condition = null, values = "(key, value) VALUES (s.key, srcValue)"))(
    errorStrs = "when there are more than one matched clauses in a merge statement, " +
      "only the last matched clause can omit the condition" :: Nil)

  testAnalysisErrorsInUnlimitedClauses(
    "error on non-empty condition following empty condition for update clauses")(
    mergeOn = "s.key = t.key",
    update(condition = null, set = "key = s.key, value = 2 * srcValue"),
    update(condition = "s.key < 3", set = "key = s.key, value = srcValue"),
    insert(condition = null, values = "(key, value) VALUES (s.key, srcValue)"))(
    errorStrs = "when there are more than one matched clauses in a merge statement, " +
      "only the last matched clause can omit the condition" :: Nil)

  testAnalysisErrorsInUnlimitedClauses(
    "error on non-empty condition following empty condition for insert clauses")(
    mergeOn = "s.key = t.key",
    update(condition = null, set = "key = s.key, value = srcValue"),
    insert(condition = null, values = "(key, value) VALUES (s.key, srcValue)"),
    insert(condition = "s.key < 3", values = "(key, value) VALUES (s.key, 1 + srcValue)"))(
    errorStrs = "when there are more than one not matched clauses in a merge statement, " +
      "only the last not matched clause can omit the condition" :: Nil)

  /* end unlimited number of merge clauses tests */
}
