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
import java.util.Locale

// scalastyle:off import.ordering.noEmptyLine
import scala.collection.JavaConverters._
import scala.language.implicitConversions

import org.apache.spark.sql.delta.DeltaOperations.ManualUpdate
import org.apache.spark.sql.delta.actions.Metadata
import org.apache.spark.sql.delta.test.DeltaSQLCommandTest
import org.apache.hadoop.fs.Path

import org.apache.spark.SparkConf
import org.apache.spark.sql.{AnalysisException, DataFrame, QueryTest, Row, SaveMode}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.NoSuchTableException
import org.apache.spark.sql.catalyst.catalog.{CatalogTable, CatalogTableType, ExternalCatalogUtils}
import org.apache.spark.sql.connector.catalog.{CatalogV2Util, Identifier, Table, TableCatalog}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.{MetadataBuilder, StructType}
import org.apache.spark.util.Utils

trait DeltaTableCreationTests
  extends QueryTest
  with SharedSparkSession {

  import testImplicits._

  val format = "delta"

  private def createDeltaTableByPath(
      path: File,
      df: DataFrame,
      tableName: String,
      partitionedBy: Seq[String] = Nil): Unit = {
    df.write
      .partitionBy(partitionedBy: _*)
      .mode(SaveMode.Append)
      .format(format)
      .save(path.getCanonicalPath)

    sql(s"""
           |CREATE TABLE delta_test
           |USING delta
           |LOCATION '${path.getCanonicalPath}'
         """.stripMargin)
  }

  private implicit def toTableIdentifier(tableName: String): TableIdentifier = {
    spark.sessionState.sqlParser.parseTableIdentifier(tableName)
  }

  protected def getTablePath(tableName: String): String = {
    new Path(spark.sessionState.catalog.getTableMetadata(tableName).location).toString
  }

  protected def getDefaultTablePath(tableName: String): String = {
    new Path(spark.sessionState.catalog.defaultTablePath(tableName)).toString
  }

  protected def getPartitioningColumns(tableName: String): Seq[String] = {
    spark.sessionState.catalog.getTableMetadata(tableName).partitionColumnNames
  }

  protected def getSchema(tableName: String): StructType = {
    spark.sessionState.catalog.getTableMetadata(tableName).schema
  }

  protected def getTableProperties(tableName: String): Map[String, String] = {
    spark.sessionState.catalog.getTableMetadata(tableName).properties
  }

  private def getDeltaLog(table: CatalogTable): DeltaLog = {
    getDeltaLog(new Path(table.storage.locationUri.get))
  }

  private def getDeltaLog(tableName: String): DeltaLog = {
    getDeltaLog(spark.sessionState.catalog.getTableMetadata(tableName))
  }

  protected def getDeltaLog(path: Path): DeltaLog = {
    DeltaLog.forTable(spark, path)
  }

  Seq("partitioned" -> Seq("v2"), "non-partitioned" -> Nil).foreach { case (isPartitioned, cols) =>
    SaveMode.values().foreach { saveMode =>
      test(s"saveAsTable to a new table (managed) - $isPartitioned, saveMode: $saveMode") {
        val tbl = "delta_test"
        withTable(tbl) {
          Seq(1L -> "a").toDF("v1", "v2")
            .write
            .partitionBy(cols: _*)
            .mode(saveMode)
            .format(format)
            .saveAsTable(tbl)

          checkDatasetUnorderly(spark.table(tbl).as[(Long, String)], 1L -> "a")
          assert(getTablePath(tbl) === getDefaultTablePath(tbl), "Table path is wrong")
          assert(getPartitioningColumns(tbl) === cols, "Partitioning columns don't match")
        }
      }

      test(s"saveAsTable to a new table (managed) - $isPartitioned," +
        s" saveMode: $saveMode (empty df)") {
        val tbl = "delta_test"
        withTable(tbl) {
          Seq(1L -> "a").toDF("v1", "v2").where("false")
            .write
            .partitionBy(cols: _*)
            .mode(saveMode)
            .format(format)
            .saveAsTable(tbl)

          checkDatasetUnorderly(spark.table(tbl).as[(Long, String)])
          assert(getTablePath(tbl) === getDefaultTablePath(tbl), "Table path is wrong")
          assert(getPartitioningColumns(tbl) === cols, "Partitioning columns don't match")
        }
      }
    }

    SaveMode.values().foreach { saveMode =>
      test(s"saveAsTable to a new table (external) - $isPartitioned, saveMode: $saveMode") {
        withTempDir { dir =>
          val tbl = "delta_test"
          withTable(tbl) {
            Seq(1L -> "a").toDF("v1", "v2")
              .write
              .partitionBy(cols: _*)
              .mode(saveMode)
              .format(format)
              .option("path", dir.getCanonicalPath)
              .saveAsTable(tbl)

            checkDatasetUnorderly(spark.table(tbl).as[(Long, String)], 1L -> "a")
            assert(getTablePath(tbl) === new Path(dir.toURI).toString.stripSuffix("/"),
              "Table path is wrong")
            assert(getPartitioningColumns(tbl) === cols, "Partitioning columns don't match")
          }
        }
      }

      test(s"saveAsTable to a new table (external) - $isPartitioned," +
        s" saveMode: $saveMode (empty df)") {
        withTempDir { dir =>
          val tbl = "delta_test"
          withTable(tbl) {
            Seq(1L -> "a").toDF("v1", "v2").where("false")
              .write
              .partitionBy(cols: _*)
              .mode(saveMode)
              .format(format)
              .option("path", dir.getCanonicalPath)
              .saveAsTable(tbl)

            checkDatasetUnorderly(spark.table(tbl).as[(Long, String)])
            assert(getTablePath(tbl) === new Path(dir.toURI).toString.stripSuffix("/"),
              "Table path is wrong")
            assert(getPartitioningColumns(tbl) === cols, "Partitioning columns don't match")
          }
        }
      }
    }

    test(s"saveAsTable (append) to an existing table - $isPartitioned") {
      withTempDir { dir =>
        val tbl = "delta_test"
        withTable(tbl) {
          createDeltaTableByPath(dir, Seq(1L -> "a").toDF("v1", "v2"), tbl, cols)

          Seq(2L -> "b").toDF("v1", "v2")
            .write
            .partitionBy(cols: _*)
            .mode(SaveMode.Append)
            .format(format)
            .saveAsTable(tbl)

          checkDatasetUnorderly(spark.table(tbl).as[(Long, String)], 1L -> "a", 2L -> "b")
        }
      }
    }

    test(s"saveAsTable (overwrite) to an existing table - $isPartitioned") {
      withTempDir { dir =>
        val tbl = "delta_test"
        withTable(tbl) {
          createDeltaTableByPath(dir, Seq(1L -> "a").toDF("v1", "v2"), tbl, cols)

          Seq(2L -> "b").toDF("v1", "v2")
            .write
            .partitionBy(cols: _*)
            .mode(SaveMode.Overwrite)
            .format(format)
            .saveAsTable(tbl)

          checkDatasetUnorderly(spark.table(tbl).as[(Long, String)], 2L -> "b")
        }
      }
    }

    test(s"saveAsTable (ignore) to an existing table - $isPartitioned") {
      withTempDir { dir =>
        val tbl = "delta_test"
        withTable(tbl) {
          createDeltaTableByPath(dir, Seq(1L -> "a").toDF("v1", "v2"), tbl, cols)

          Seq(2L -> "b").toDF("v1", "v2")
            .write
            .partitionBy(cols: _*)
            .mode(SaveMode.Ignore)
            .format(format)
            .saveAsTable(tbl)

          checkDatasetUnorderly(spark.table(tbl).as[(Long, String)], 1L -> "a")
        }
      }
    }

    test(s"saveAsTable (error if exists) to an existing table - $isPartitioned") {
      withTempDir { dir =>
        val tbl = "delta_test"
        withTable(tbl) {
          createDeltaTableByPath(dir, Seq(1L -> "a").toDF("v1", "v2"), tbl, cols)

          val e = intercept[AnalysisException] {
            Seq(2L -> "b").toDF("v1", "v2")
              .write
              .partitionBy(cols: _*)
              .mode(SaveMode.ErrorIfExists)
              .format(format)
              .saveAsTable(tbl)
          }
          assert(e.getMessage.contains(tbl))
          assert(e.getMessage.contains("already exists"))

          checkDatasetUnorderly(spark.table(tbl).as[(Long, String)], 1L -> "a")
        }
      }
    }
  }

  test("saveAsTable (append) + insert to a table created without a schema") {
    withTempDir { dir =>
      withTable("delta_test") {
        Seq(1L -> "a").toDF("v1", "v2")
          .write
          .mode(SaveMode.Append)
          .partitionBy("v2")
          .format(format)
          .option("path", dir.getCanonicalPath)
          .saveAsTable("delta_test")

        // Out of order
        Seq("b" -> 2L).toDF("v2", "v1")
          .write
          .partitionBy("v2")
          .mode(SaveMode.Append)
          .format(format)
          .saveAsTable("delta_test")

        Seq(3L -> "c").toDF("v1", "v2")
          .write
          .format(format)
          .insertInto("delta_test")

        checkDatasetUnorderly(
          spark.table("delta_test").as[(Long, String)], 1L -> "a", 2L -> "b", 3L -> "c")
      }
    }
  }

  test("saveAsTable to a table created with an invalid partitioning column") {
    withTempDir { dir =>
      withTable("delta_test") {
        Seq(1L -> "a").toDF("v1", "v2")
          .write
          .mode(SaveMode.Append)
          .partitionBy("v2")
          .format(format)
          .option("path", dir.getCanonicalPath)
          .saveAsTable("delta_test")
        checkDatasetUnorderly(spark.table("delta_test").as[(Long, String)], 1L -> "a")

        var ex = intercept[Exception] {
          Seq("b" -> 2L).toDF("v2", "v1")
            .write
            .partitionBy("v1")
            .mode(SaveMode.Append)
            .format(format)
            .saveAsTable("delta_test")
        }.getMessage
        assert(ex.contains("not match"))
        assert(ex.contains("partition"))
        checkDatasetUnorderly(spark.table("delta_test").as[(Long, String)], 1L -> "a")

        ex = intercept[Exception] {
          Seq("b" -> 2L).toDF("v3", "v1")
            .write
            .partitionBy("v1")
            .mode(SaveMode.Append)
            .format(format)
            .saveAsTable("delta_test")
        }.getMessage
        assert(ex.contains("not match"))
        assert(ex.contains("partition"))
        checkDatasetUnorderly(spark.table("delta_test").as[(Long, String)], 1L -> "a")

        Seq("b" -> 2L).toDF("v1", "v3")
          .write
          .partitionBy("v1")
          .mode(SaveMode.Ignore)
          .format(format)
          .saveAsTable("delta_test")
        checkDatasetUnorderly(spark.table("delta_test").as[(Long, String)], 1L -> "a")

        ex = intercept[AnalysisException] {
          Seq("b" -> 2L).toDF("v1", "v3")
            .write
            .partitionBy("v1")
            .mode(SaveMode.ErrorIfExists)
            .format(format)
            .saveAsTable("delta_test")
        }.getMessage
        assert(ex.contains("delta_test"))
        assert(ex.contains("already exists"))
        checkDatasetUnorderly(spark.table("delta_test").as[(Long, String)], 1L -> "a")
      }
    }
  }

  testQuietly("cannot create delta table with an invalid column name") {
    val tableName = "delta_test"
    withTable(tableName) {
      val tableLoc =
        new File(spark.sessionState.catalog.defaultTablePath(TableIdentifier(tableName)))
      Utils.deleteRecursively(tableLoc)
      val ex = intercept[AnalysisException] {
        Seq(1, 2, 3).toDF("a column name with spaces")
          .write
          .format(format)
          .mode(SaveMode.Overwrite)
          .saveAsTable(tableName)
      }
      assert(ex.getMessage.contains("contains invalid character(s)"))
      assert(!tableLoc.exists())

      val ex2 = intercept[AnalysisException] {
        sql(s"CREATE TABLE $tableName(`a column name with spaces` LONG, b String) USING delta")
      }
      assert(ex2.getMessage.contains("contains invalid character(s)"))
      assert(!tableLoc.exists())
    }
  }

  testQuietly("cannot create delta table when using buckets") {
    withTable("bucketed_table") {
      val e = intercept[AnalysisException] {
        Seq(1L -> "a").toDF("i", "j").write
          .format(format)
          .partitionBy("i")
          .bucketBy(numBuckets = 8, "j")
          .saveAsTable("bucketed_table")
      }
      assert(e.getMessage.toLowerCase(Locale.ROOT).contains(
        "`bucketing` is not supported for delta tables"))
    }
  }

  test("save without a path") {
    val e = intercept[IllegalArgumentException] {
      Seq(1L -> "a").toDF("i", "j").write
        .format(format)
        .partitionBy("i")
        .save()
    }
    assert(e.getMessage.toLowerCase(Locale.ROOT).contains("'path' is not specified"))
  }

  test("save with an unknown partition column") {
    withTempDir { dir =>
      val path = dir.getCanonicalPath
      val e = intercept[AnalysisException] {
        Seq(1L -> "a").toDF("i", "j").write
          .format(format)
          .partitionBy("unknownColumn")
          .save(path)
      }
      assert(e.getMessage.contains("unknownColumn"))
    }
  }

  test("create a table with special column names") {
    withTable("t") {
      Seq(1 -> "a").toDF("x.x", "y.y").write.format(format).saveAsTable("t")
      Seq(2 -> "b").toDF("x.x", "y.y").write.format(format).mode("append").saveAsTable("t")
      checkAnswer(spark.table("t"), Row(1, "a") :: Row(2, "b") :: Nil)
    }
  }

  testQuietly("saveAsTable (overwrite) to a non-partitioned table created with different paths") {
    withTempDir { dir1 =>
      withTempDir { dir2 =>
        withTable("delta_test") {
          Seq(1L -> "a").toDF("v1", "v2")
            .write
            .mode(SaveMode.Append)
            .format(format)
            .option("path", dir1.getCanonicalPath)
            .saveAsTable("delta_test")

          val ex = intercept[AnalysisException] {
            Seq((3L, "c")).toDF("v1", "v2")
              .write
              .mode(SaveMode.Overwrite)
              .format(format)
              .option("path", dir2.getCanonicalPath)
              .saveAsTable("delta_test")
          }.getMessage
          assert(ex.contains("The location of the existing table `default`.`delta_test`"))

          checkAnswer(
            spark.table("delta_test"), Row(1L, "a") :: Nil)
        }
      }
    }
  }

  test("saveAsTable (append) to a non-partitioned table created without path") {
    withTempDir { dir =>
      withTable("delta_test") {
        Seq(1L -> "a").toDF("v1", "v2")
          .write
          .mode(SaveMode.Overwrite)
          .format(format)
          .option("path", dir.getCanonicalPath)
          .saveAsTable("delta_test")

        Seq((3L, "c")).toDF("v1", "v2")
          .write
          .mode(SaveMode.Append)
          .format(format)
          .saveAsTable("delta_test")

        checkAnswer(
          spark.table("delta_test"), Row(1L, "a") :: Row(3L, "c") :: Nil)
      }
    }
  }

  test("saveAsTable (append) to a non-partitioned table created with identical paths") {
    withTempDir { dir =>
      withTable("delta_test") {
        Seq(1L -> "a").toDF("v1", "v2")
          .write
          .mode(SaveMode.Overwrite)
          .format(format)
          .option("path", dir.getCanonicalPath)
          .saveAsTable("delta_test")

        Seq((3L, "c")).toDF("v1", "v2")
          .write
          .mode(SaveMode.Append)
          .format(format)
          .option("path", dir.getCanonicalPath)
          .saveAsTable("delta_test")

        checkAnswer(
          spark.table("delta_test"), Row(1L, "a") :: Row(3L, "c") :: Nil)
      }
    }
  }

  test("overwrite mode saveAsTable without path shouldn't create managed table") {
    withTempDir { dir =>
      withTable("delta_test") {
        sql(
          s"""CREATE TABLE delta_test
             |USING delta
             |LOCATION '${dir.getAbsolutePath}'
             |AS SELECT 1 as a
          """.stripMargin)
        val deltaLog = DeltaLog.forTable(spark, dir)
        assert(deltaLog.snapshot.version === 0, "CTAS should be a single commit")

        checkAnswer(spark.table("delta_test"), Row(1) :: Nil)

        Seq((2, "key")).toDF("a", "b")
          .write
          .mode(SaveMode.Overwrite)
          .option(DeltaOptions.OVERWRITE_SCHEMA_OPTION, "true")
          .format(format)
          .saveAsTable("delta_test")

        assert(deltaLog.snapshot.version === 1, "Overwrite mode shouldn't create new managed table")

        checkAnswer(spark.table("delta_test"), Row(2, "key") :: Nil)

      }
    }
  }

  testQuietly("reject table creation with column names that only differ by case") {
    withSQLConf(SQLConf.CASE_SENSITIVE.key -> "true") {
      withTempDir { dir =>
        withTable("delta_test") {
          intercept[AnalysisException] {
            sql(
              s"""CREATE TABLE delta_test
                 |USING delta
                 |LOCATION '${dir.getAbsolutePath}'
                 |AS SELECT 1 as a, 2 as A
              """.stripMargin)
          }

          intercept[AnalysisException] {
            sql(
              s"""CREATE TABLE delta_test(
                 |  a string,
                 |  A string
                 |)
                 |USING delta
                 |LOCATION '${dir.getAbsolutePath}'
              """.stripMargin)
          }

          intercept[AnalysisException] {
            sql(
              s"""CREATE TABLE delta_test(
                 |  a string,
                 |  b string
                 |)
                 |partitioned by (a, a)
                 |USING delta
                 |LOCATION '${dir.getAbsolutePath}'
              """.stripMargin)
          }
        }
      }
    }
  }

  testQuietly("saveAsTable into a view throws exception around view definition") {
    withTempDir { dir =>
      val viewName = "delta_test"
      withView(viewName) {
        Seq((1, "key")).toDF("a", "b").write.format(format).save(dir.getCanonicalPath)
        sql(s"create view $viewName as select * from delta.`${dir.getCanonicalPath}`")
        val e = intercept[AnalysisException] {
          Seq((2, "key")).toDF("a", "b").write.format(format).mode("append").saveAsTable(viewName)
        }
        assert(e.getMessage.contains("a view"))
      }
    }
  }

  testQuietly("saveAsTable into a parquet table throws exception around format") {
    withTempPath { dir =>
      val tabName = "delta_test"
      withTable(tabName) {
        Seq((1, "key")).toDF("a", "b").write.format("parquet")
          .option("path", dir.getCanonicalPath).saveAsTable(tabName)
        intercept[AnalysisException] {
          Seq((2, "key")).toDF("a", "b").write.format("delta").mode("append").saveAsTable(tabName)
        }
      }
    }
  }

  test("create table with schema and path") {
    withTempDir { dir =>
      withTable("delta_test") {
        sql(
          s"""
             |CREATE TABLE delta_test(a LONG, b String)
             |USING delta
             |OPTIONS('path'='${dir.getCanonicalPath}')""".stripMargin)
        sql("INSERT INTO delta_test SELECT 1, 'a'")
        checkDatasetUnorderly(
          sql("SELECT * FROM delta_test").as[(Long, String)],
          1L -> "a")

      }
    }
  }

  testQuietly("failed to create a table and then able to recreate it") {
    withTable("delta_test") {
      val e = intercept[AnalysisException] {
        sql("CREATE TABLE delta_test USING delta")
      }.getMessage
      assert(e.contains("but the schema is not specified"))

      sql("CREATE TABLE delta_test(a LONG, b String) USING delta")

      sql("INSERT INTO delta_test SELECT 1, 'a'")

      checkDatasetUnorderly(
        sql("SELECT * FROM delta_test").as[(Long, String)],
        1L -> "a")
    }
  }

  test("create external table without schema") {
    withTempDir { dir =>
      withTable("delta_test", "delta_test1") {
        Seq(1L -> "a").toDF()
          .selectExpr("_1 as v1", "_2 as v2")
          .write
          .mode("append")
          .partitionBy("v2")
          .format("delta")
          .save(dir.getCanonicalPath)

        sql(s"""
               |CREATE TABLE delta_test
               |USING delta
               |OPTIONS('path'='${dir.getCanonicalPath}')
            """.stripMargin)

        spark.catalog.createTable("delta_test1", dir.getCanonicalPath, "delta")

        checkDatasetUnorderly(
          sql("SELECT * FROM delta_test").as[(Long, String)],
          1L -> "a")

        checkDatasetUnorderly(
          sql("SELECT * FROM delta_test1").as[(Long, String)],
          1L -> "a")
      }
    }
  }

  testQuietly("create managed table without schema") {
    withTable("delta_test") {
      val e = intercept[AnalysisException] {
        sql("CREATE TABLE delta_test USING delta")
      }.getMessage
      assert(e.contains("but the schema is not specified"))
    }
  }

  testQuietly("reject creating a delta table pointing to non-delta files") {
    withTempPath { dir =>
      withTable("delta_test") {
        val path = dir.getCanonicalPath
        Seq(1L -> "a").toDF("col1", "col2").write.parquet(path)
        val e = intercept[AnalysisException] {
          sql(
            s"""
               |CREATE TABLE delta_test (col1 int, col2 string)
               |USING delta
               |LOCATION '$path'
             """.stripMargin)
        }.getMessage
        assert(e.contains(
          "Cannot create table ('`default`.`delta_test`'). The associated location"))
      }
    }
  }

  testQuietly("create external table without schema but using non-delta files") {
    withTempDir { dir =>
      withTable("delta_test") {
        Seq(1L -> "a").toDF().selectExpr("_1 as v1", "_2 as v2").write
          .mode("append").partitionBy("v2").format("parquet").save(dir.getCanonicalPath)
        val e = intercept[AnalysisException] {
          sql(s"CREATE TABLE delta_test USING delta LOCATION '${dir.getCanonicalPath}'")
        }.getMessage
        assert(e.contains("but there is no transaction log"))
      }
    }
  }

  testQuietly("create external table without schema and input files") {
    withTempDir { dir =>
      withTable("delta_test") {
        val e = intercept[AnalysisException] {
          sql(s"CREATE TABLE delta_test USING delta LOCATION '${dir.getCanonicalPath}'")
        }.getMessage
        assert(e.contains("but the schema is not specified") && e.contains("input path is empty"))
      }
    }
  }

  test("create and drop delta table - external") {
    val catalog = spark.sessionState.catalog
    withTempDir { tempDir =>
      withTable("delta_test") {
        sql("CREATE TABLE delta_test(a LONG, b String) USING delta " +
          s"OPTIONS (path='${tempDir.getCanonicalPath}')")
        val table = catalog.getTableMetadata(TableIdentifier("delta_test"))
        assert(table.tableType == CatalogTableType.EXTERNAL)
        assert(table.provider.contains("delta"))

        // Query the data and the metadata directly via the DeltaLog
        val deltaLog = getDeltaLog(table)

        assert(deltaLog.snapshot.schema == new StructType().add("a", "long").add("b", "string"))
        assert(deltaLog.snapshot.metadata.partitionSchema == new StructType())

        assert(deltaLog.snapshot.schema == getSchema("delta_test"))
        assert(getPartitioningColumns("delta_test").isEmpty)

        // External catalog does not contain the schema and partition column names.
        val externalTable = catalog.externalCatalog.getTable("default", "delta_test")
        assert(externalTable.schema == new StructType())
        assert(externalTable.partitionColumnNames.isEmpty)

        sql("INSERT INTO delta_test SELECT 1, 'a'")
        checkDatasetUnorderly(
          sql("SELECT * FROM delta_test").as[(Long, String)],
          1L -> "a")

        sql("DROP TABLE delta_test")
        intercept[NoSuchTableException](catalog.getTableMetadata(TableIdentifier("delta_test")))
        // Verify that the underlying location is not deleted for an external table
        checkAnswer(spark.read.format("delta")
          .load(new Path(tempDir.getCanonicalPath).toString), Seq(Row(1L, "a")))
      }
    }
  }

  test("create and drop delta table - managed") {
    val catalog = spark.sessionState.catalog
    withTable("delta_test") {
      sql("CREATE TABLE delta_test(a LONG, b String) USING delta")
      val table = catalog.getTableMetadata(TableIdentifier("delta_test"))
      assert(table.tableType == CatalogTableType.MANAGED)
      assert(table.provider.contains("delta"))

      // Query the data and the metadata directly via the DeltaLog
      val deltaLog = getDeltaLog(table)

      assert(deltaLog.snapshot.schema == new StructType().add("a", "long").add("b", "string"))
      assert(deltaLog.snapshot.metadata.partitionSchema == new StructType())

      assert(deltaLog.snapshot.schema == getSchema("delta_test"))
      assert(getPartitioningColumns("delta_test").isEmpty)
      assert(getSchema("delta_test") == new StructType().add("a", "long").add("b", "string"))

      // External catalog does not contain the schema and partition column names.
      val externalTable = catalog.externalCatalog.getTable("default", "delta_test")
      assert(externalTable.schema == new StructType())
      assert(externalTable.partitionColumnNames.isEmpty)

      sql("INSERT INTO delta_test SELECT 1, 'a'")
      checkDatasetUnorderly(
        sql("SELECT * FROM delta_test").as[(Long, String)],
        1L -> "a")

      sql("DROP TABLE delta_test")
      intercept[NoSuchTableException](catalog.getTableMetadata(TableIdentifier("delta_test")))
      // Verify that the underlying location is deleted for a managed table
      assert(!new File(table.location).exists())
    }
  }

  test("create table using - with partitioned by") {
    val catalog = spark.sessionState.catalog
    withTable("delta_test") {
      sql("CREATE TABLE delta_test(a LONG, b String) USING delta PARTITIONED BY (a)")
      val table = catalog.getTableMetadata(TableIdentifier("delta_test"))
      assert(table.tableType == CatalogTableType.MANAGED)
      assert(table.provider.contains("delta"))


      // Query the data and the metadata directly via the DeltaLog
      val deltaLog = getDeltaLog(table)

      assert(deltaLog.snapshot.schema == new StructType().add("a", "long").add("b", "string"))
      assert(deltaLog.snapshot.metadata.partitionSchema == new StructType().add("a", "long"))

      assert(deltaLog.snapshot.schema == getSchema("delta_test"))
      assert(getPartitioningColumns("delta_test") == Seq("a"))
      assert(getSchema("delta_test") == new StructType().add("a", "long").add("b", "string"))

      // External catalog does not contain the schema and partition column names.
      val externalTable = catalog.externalCatalog.getTable("default", "delta_test")
      assert(externalTable.schema == new StructType())
      assert(externalTable.partitionColumnNames.isEmpty)

      sql("INSERT INTO delta_test SELECT 1, 'a'")

      val path = new File(new File(table.storage.locationUri.get), "a=1")
      assert(path.listFiles().nonEmpty)

      checkDatasetUnorderly(
        sql("SELECT * FROM delta_test").as[(Long, String)],
        1L -> "a")
    }
  }

  test("CTAS a managed table with the existing empty directory") {
    val tableLoc = new File(spark.sessionState.catalog.defaultTablePath(TableIdentifier("tab1")))
    try {
      tableLoc.mkdir()
      withTable("tab1") {
        sql("CREATE TABLE tab1 USING delta AS SELECT 2, 'b'")
        checkAnswer(spark.table("tab1"), Row(2, "b"))
      }
    } finally {
      waitForTasksToFinish()
      Utils.deleteRecursively(tableLoc)
    }
  }

  test("create a managed table with the existing empty directory") {
    val tableLoc = new File(spark.sessionState.catalog.defaultTablePath(TableIdentifier("tab1")))
    try {
      tableLoc.mkdir()
      withTable("tab1") {
        sql("CREATE TABLE tab1 (col1 int, col2 string) USING delta")
        sql("INSERT INTO tab1 VALUES (2, 'B')")
        checkAnswer(spark.table("tab1"), Row(2, "B"))
      }
    } finally {
      waitForTasksToFinish()
      Utils.deleteRecursively(tableLoc)
    }
  }

  testQuietly("create a managed table with the existing non-empty directory") {
    withTable("tab1") {
      val tableLoc = new File(spark.sessionState.catalog.defaultTablePath(TableIdentifier("tab1")))
      try {
        // create an empty hidden file
        tableLoc.mkdir()
        val hiddenGarbageFile = new File(tableLoc.getCanonicalPath, ".garbage")
        hiddenGarbageFile.createNewFile()
        var ex = intercept[AnalysisException] {
          sql("CREATE TABLE tab1 USING delta AS SELECT 2, 'b'")
        }.getMessage
        assert(ex.contains("Cannot create table"))

        ex = intercept[AnalysisException] {
          sql("CREATE TABLE tab1 (col1 int, col2 string) USING delta")
        }.getMessage
        assert(ex.contains("Cannot create table"))
      } finally {
        waitForTasksToFinish()
        Utils.deleteRecursively(tableLoc)
      }
    }
  }

  test("create table with table properties") {
    withTable("delta_test") {
      sql(s"""
             |CREATE TABLE delta_test(a LONG, b String)
             |USING delta
             |TBLPROPERTIES(
             |  'delta.logRetentionDuration' = '2 weeks',
             |  'delta.checkpointInterval' = '20',
             |  'key' = 'value'
             |)
          """.stripMargin)

      val deltaLog = getDeltaLog("delta_test")

      val snapshot = deltaLog.update()
      assert(snapshot.metadata.configuration == Map(
        "delta.logRetentionDuration" -> "2 weeks",
        "delta.checkpointInterval" -> "20",
        "key" -> "value"))
      assert(deltaLog.deltaRetentionMillis == 2 * 7 * 24 * 60 * 60 * 1000)
      assert(deltaLog.checkpointInterval == 20)
    }
  }

  test("create table with table properties - case insensitivity") {
    withTable("delta_test") {
      sql(s"""
             |CREATE TABLE delta_test(a LONG, b String)
             |USING delta
             |TBLPROPERTIES(
             |  'dEltA.lOgrEteNtiOndURaTion' = '2 weeks',
             |  'DelTa.ChEckPoiNtinTervAl' = '20'
             |)
          """.stripMargin)

      val deltaLog = getDeltaLog("delta_test")

      val snapshot = deltaLog.update()
      assert(snapshot.metadata.configuration ==
        Map("delta.logRetentionDuration" -> "2 weeks", "delta.checkpointInterval" -> "20"))
      assert(deltaLog.deltaRetentionMillis == 2 * 7 * 24 * 60 * 60 * 1000)
      assert(deltaLog.checkpointInterval == 20)
    }
  }

  test(
      "create table with table properties - case insensitivity with existing configuration") {
    withTempDir { tempDir =>
      withTable("delta_test") {
        val path = tempDir.getCanonicalPath

        val deltaLog = getDeltaLog(new Path(path))

        val txn = deltaLog.startTransaction()
        txn.commit(Seq(Metadata(
          schemaString = new StructType().add("a", "long").add("b", "string").json,
          configuration = Map(
            "delta.logRetentionDuration" -> "2 weeks",
            "delta.checkpointInterval" -> "20",
            "key" -> "value"))),
          ManualUpdate)

        sql(s"""
               |CREATE TABLE delta_test(a LONG, b String)
               |USING delta LOCATION '$path'
               |TBLPROPERTIES(
               |  'dEltA.lOgrEteNtiOndURaTion' = '2 weeks',
               |  'DelTa.ChEckPoiNtinTervAl' = '20',
               |  'key' = "value"
               |)
            """.stripMargin)

        val snapshot = deltaLog.update()
        assert(snapshot.metadata.configuration == Map(
          "delta.logRetentionDuration" -> "2 weeks",
          "delta.checkpointInterval" -> "20",
          "key" -> "value"))
        assert(deltaLog.deltaRetentionMillis == 2 * 7 * 24 * 60 * 60 * 1000)
        assert(deltaLog.checkpointInterval == 20)
      }
    }
  }


  test("schema mismatch between DDL and table location should throw an error") {
    withTempDir { tempDir =>
      withTable("delta_test") {
        val deltaLog = getDeltaLog(new Path(tempDir.getCanonicalPath))

        val txn = deltaLog.startTransaction()
        txn.commit(
          Seq(Metadata(schemaString = new StructType().add("a", "long").add("b", "long").json)),
          DeltaOperations.ManualUpdate)

        val ex = intercept[AnalysisException] {
          sql("CREATE TABLE delta_test(a LONG, b String)" +
            s" USING delta OPTIONS (path '${tempDir.getCanonicalPath}')")
        }
        assert(ex.getMessage.contains("The specified schema does not match the existing schema"))
        assert(ex.getMessage.contains("Specified type for b is different"))

        val ex1 = intercept[AnalysisException] {
          sql("CREATE TABLE delta_test(a LONG)" +
            s" USING delta OPTIONS (path '${tempDir.getCanonicalPath}')")
        }
        assert(ex1.getMessage.contains("The specified schema does not match the existing schema"))
        assert(ex1.getMessage.contains("Specified schema is missing field"))

        val ex2 = intercept[AnalysisException] {
          sql("CREATE TABLE delta_test(a LONG, b String, c INT, d LONG)" +
            s" USING delta OPTIONS (path '${tempDir.getCanonicalPath}')")
        }
        assert(ex2.getMessage.contains("The specified schema does not match the existing schema"))
        assert(ex2.getMessage.contains("Specified schema has additional field"))
        assert(ex2.getMessage.contains("Specified type for b is different"))
      }
    }
  }

  test(
      "schema metadata mismatch between DDL and table location should throw an error") {
    withTempDir { tempDir =>
      withTable("delta_test") {
        val deltaLog = getDeltaLog(new Path(tempDir.getCanonicalPath))

        val txn = deltaLog.startTransaction()
        txn.commit(
          Seq(Metadata(schemaString = new StructType().add("a", "long")
            .add("b", "string", nullable = true,
              new MetadataBuilder().putBoolean("pii", value = true).build()).json)),
          DeltaOperations.ManualUpdate)
        val ex = intercept[AnalysisException] {
          sql("CREATE TABLE delta_test(a LONG, b String)" +
            s" USING delta OPTIONS (path '${tempDir.getCanonicalPath}')")
        }
        assert(ex.getMessage.contains("The specified schema does not match the existing schema"))
        assert(ex.getMessage.contains("metadata for field b is different"))
      }
    }
  }

  test(
      "partition schema mismatch between DDL and table location should throw an error") {
    withTempDir { tempDir =>
      withTable("delta_test") {
        val deltaLog = getDeltaLog(new Path(tempDir.getCanonicalPath))

        val txn = deltaLog.startTransaction()
        txn.commit(
          Seq(Metadata(
            schemaString = new StructType().add("a", "long").add("b", "string").json,
            partitionColumns = Seq("a"))),
          DeltaOperations.ManualUpdate)
        val ex = intercept[AnalysisException](sql("CREATE TABLE delta_test(a LONG, b String)" +
          s" USING delta PARTITIONED BY(b) LOCATION '${tempDir.getCanonicalPath}'"))
        assert(ex.getMessage.contains(
          "The specified partitioning does not match the existing partitioning"))
      }
    }
  }

  testQuietly("create table with unknown table properties should throw an error") {
    withTempDir { tempDir =>
      withTable("delta_test") {
        val ex = intercept[AnalysisException](sql(
          s"""
             |CREATE TABLE delta_test(a LONG, b String)
             |USING delta LOCATION '${tempDir.getCanonicalPath}'
             |TBLPROPERTIES('delta.key' = 'value')
          """.stripMargin))
        assert(ex.getMessage.contains(
          "Unknown configuration was specified: delta.key"))
      }
    }
  }

  testQuietly("create table with invalid table properties should throw an error") {
    withTempDir { tempDir =>
      withTable("delta_test") {
        val ex1 = intercept[IllegalArgumentException](sql(
          s"""
             |CREATE TABLE delta_test(a LONG, b String)
             |USING delta LOCATION '${tempDir.getCanonicalPath}'
             |TBLPROPERTIES('delta.randomPrefixLength' = '-1')
          """.stripMargin))
        assert(ex1.getMessage.contains(
          "randomPrefixLength needs to be greater than 0."))

        val ex2 = intercept[IllegalArgumentException](sql(
          s"""
             |CREATE TABLE delta_test(a LONG, b String)
             |USING delta LOCATION '${tempDir.getCanonicalPath}'
             |TBLPROPERTIES('delta.randomPrefixLength' = 'value')
          """.stripMargin))
        assert(ex2.getMessage.contains(
          "randomPrefixLength needs to be greater than 0."))
      }
    }
  }

  test(
      "table properties mismatch between DDL and table location should throw an error") {
    withTempDir { tempDir =>
      withTable("delta_test") {
        val deltaLog = getDeltaLog(new Path(tempDir.getCanonicalPath))

        val txn = deltaLog.startTransaction()
        txn.commit(
          Seq(Metadata(
            schemaString = new StructType().add("a", "long").add("b", "string").json)),
          DeltaOperations.ManualUpdate)
        val ex = intercept[AnalysisException] {
          sql(
            s"""
               |CREATE TABLE delta_test(a LONG, b String)
               |USING delta LOCATION '${tempDir.getCanonicalPath}'
               |TBLPROPERTIES('delta.randomizeFilePrefixes' = 'true')
            """.stripMargin)
        }

        assert(ex.getMessage.contains(
          "The specified properties do not match the existing properties"))
      }
    }
  }

  test("create table on an existing table location") {
    val catalog = spark.sessionState.catalog
    withTempDir { tempDir =>
      withTable("delta_test") {
        val deltaLog = getDeltaLog(new Path(tempDir.getCanonicalPath))

        val txn = deltaLog.startTransaction()
        txn.commit(
          Seq(Metadata(
            schemaString = new StructType().add("a", "long").add("b", "string").json,
            partitionColumns = Seq("b"))),
          DeltaOperations.ManualUpdate)
        sql("CREATE TABLE delta_test(a LONG, b String) USING delta " +
          s"OPTIONS (path '${tempDir.getCanonicalPath}') PARTITIONED BY(b)")
        val table = catalog.getTableMetadata(TableIdentifier("delta_test"))
        assert(table.tableType == CatalogTableType.EXTERNAL)
        assert(table.provider.contains("delta"))

        // Query the data and the metadata directly via the DeltaLog
        val deltaLog2 = getDeltaLog(table)

        assert(deltaLog2.snapshot.schema == new StructType().add("a", "long").add("b", "string"))
        assert(deltaLog2.snapshot.metadata.partitionSchema == new StructType().add("b", "string"))

        assert(getSchema("delta_test") === deltaLog2.snapshot.schema)
        assert(getPartitioningColumns("delta_test") === Seq("b"))

        // External catalog does not contain the schema and partition column names.
        val externalTable = spark.sessionState.catalog.externalCatalog
          .getTable("default", "delta_test")
        assert(externalTable.schema == new StructType())
        assert(externalTable.partitionColumnNames.isEmpty)
      }
    }
  }

  test("create datasource table with a non-existing location") {
    withTempPath { dir =>
      withTable("t") {
        spark.sql(s"CREATE TABLE t(a int, b int) USING delta LOCATION '${dir.toURI}'")

        val table = spark.sessionState.catalog.getTableMetadata(TableIdentifier("t"))
        assert(table.location == makeQualifiedPath(dir.getAbsolutePath))

        spark.sql("INSERT INTO TABLE t SELECT 1, 2")
        assert(dir.exists())

        checkDatasetUnorderly(
          sql("SELECT * FROM t").as[(Int, Int)],
          1 -> 2)
      }
    }

    // partition table
    withTempPath { dir =>
      withTable("t1") {
        spark.sql(
          s"CREATE TABLE t1(a int, b int) USING delta PARTITIONED BY(a) LOCATION '${dir.toURI}'")

        val table = spark.sessionState.catalog.getTableMetadata(TableIdentifier("t1"))
        assert(table.location == makeQualifiedPath(dir.getAbsolutePath))

        Seq((1, 2)).toDF("a", "b")
          .write.format("delta").mode("append").save(table.location.toString)
        val read = spark.read.format("delta").load(table.location.toString)
        checkAnswer(read, Seq(Row(1, 2)))

        val partDir = new File(dir, "a=1")
        assert(partDir.exists())
      }
    }
  }

  Seq(true, false).foreach { shouldDelete =>
    val tcName = if (shouldDelete) "non-existing" else "existing"
    test(s"CTAS for external data source table with $tcName location") {
      val catalog = spark.sessionState.catalog
      withTable("t", "t1") {
        withTempDir { dir =>
          if (shouldDelete) dir.delete()
          spark.sql(
            s"""
               |CREATE TABLE t
               |USING delta
               |LOCATION '${dir.toURI}'
               |AS SELECT 3 as a, 4 as b, 1 as c, 2 as d
             """.stripMargin)
          val table = spark.sessionState.catalog.getTableMetadata(TableIdentifier("t"))
          assert(table.tableType == CatalogTableType.EXTERNAL)
          assert(table.provider.contains("delta"))
          assert(table.location == makeQualifiedPath(dir.getAbsolutePath))

          // Query the data and the metadata directly via the DeltaLog
          val deltaLog = getDeltaLog(table)

          assert(deltaLog.snapshot.schema == new StructType()
            .add("a", "integer").add("b", "integer")
            .add("c", "integer").add("d", "integer"))
          assert(deltaLog.snapshot.metadata.partitionSchema == new StructType())

          assert(getSchema("t") == deltaLog.snapshot.schema)
          assert(getPartitioningColumns("t").isEmpty)

          // External catalog does not contain the schema and partition column names.
          val externalTable = catalog.externalCatalog.getTable("default", "t")
          assert(externalTable.schema == new StructType())
          assert(externalTable.partitionColumnNames.isEmpty)

          // Query the table
          checkAnswer(spark.table("t"), Row(3, 4, 1, 2))

          // Directly query the reservoir
          checkAnswer(spark.read.format("delta")
            .load(new Path(table.storage.locationUri.get).toString), Seq(Row(3, 4, 1, 2)))
        }
        // partition table
        withTempDir { dir =>
          if (shouldDelete) dir.delete()
          spark.sql(
            s"""
               |CREATE TABLE t1
               |USING delta
               |PARTITIONED BY(a, b)
               |LOCATION '${dir.toURI}'
               |AS SELECT 3 as a, 4 as b, 1 as c, 2 as d
             """.stripMargin)
          val table = spark.sessionState.catalog.getTableMetadata(TableIdentifier("t1"))
          assert(table.tableType == CatalogTableType.EXTERNAL)
          assert(table.provider.contains("delta"))
          assert(table.location == makeQualifiedPath(dir.getAbsolutePath))

          // Query the data and the metadata directly via the DeltaLog
          val deltaLog = getDeltaLog(table)

          assert(deltaLog.snapshot.schema == new StructType()
            .add("a", "integer").add("b", "integer")
            .add("c", "integer").add("d", "integer"))
          assert(deltaLog.snapshot.metadata.partitionSchema == new StructType()
            .add("a", "integer").add("b", "integer"))

          assert(getSchema("t1") == deltaLog.snapshot.schema)
          assert(getPartitioningColumns("t1") == Seq("a", "b"))

          // External catalog does not contain the schema and partition column names.
          val externalTable = catalog.externalCatalog.getTable("default", "t1")
          assert(externalTable.schema == new StructType())
          assert(externalTable.partitionColumnNames.isEmpty)

          // Query the table
          checkAnswer(spark.table("t1"), Row(3, 4, 1, 2))

          // Directly query the reservoir
          checkAnswer(spark.read.format("delta")
            .load(new Path(table.storage.locationUri.get).toString), Seq(Row(3, 4, 1, 2)))
        }
      }
    }
  }

  test("CTAS with table properties") {
    withTable("delta_test") {
      sql(
        s"""
           |CREATE TABLE delta_test
           |USING delta
           |TBLPROPERTIES(
           |  'delta.logRetentionDuration' = '2 weeks',
           |  'delta.checkpointInterval' = '20',
           |  'key' = 'value'
           |)
           |AS SELECT 3 as a, 4 as b, 1 as c, 2 as d
        """.stripMargin)

      val deltaLog = getDeltaLog("delta_test")

      val snapshot = deltaLog.update()
      assert(snapshot.metadata.configuration == Map(
        "delta.logRetentionDuration" -> "2 weeks",
        "delta.checkpointInterval" -> "20",
        "key" -> "value"))
      assert(deltaLog.deltaRetentionMillis == 2 * 7 * 24 * 60 * 60 * 1000)
      assert(deltaLog.checkpointInterval == 20)
    }
  }

  test("CTAS with table properties - case insensitivity") {
    withTable("delta_test") {
      sql(
        s"""
           |CREATE TABLE delta_test
           |USING delta
           |TBLPROPERTIES(
           |  'dEltA.lOgrEteNtiOndURaTion' = '2 weeks',
           |  'DelTa.ChEckPoiNtinTervAl' = '20'
           |)
           |AS SELECT 3 as a, 4 as b, 1 as c, 2 as d
        """.stripMargin)

      val deltaLog = getDeltaLog("delta_test")

      val snapshot = deltaLog.update()
      assert(snapshot.metadata.configuration ==
        Map("delta.logRetentionDuration" -> "2 weeks", "delta.checkpointInterval" -> "20"))
      assert(deltaLog.deltaRetentionMillis == 2 * 7 * 24 * 60 * 60 * 1000)
      assert(deltaLog.checkpointInterval == 20)
    }
  }

  testQuietly("CTAS external table with existing data should fail") {
    withTable("t") {
      withTempDir { dir =>
        dir.delete()
        Seq((3, 4)).toDF("a", "b")
          .write.format("delta")
          .save(dir.toString)
        val ex = intercept[AnalysisException](spark.sql(
          s"""
             |CREATE TABLE t
             |USING delta
             |LOCATION '${dir.toURI}'
             |AS SELECT 1 as a, 2 as b
             """.stripMargin))
        assert(ex.getMessage.contains("Cannot create table"))
      }
    }

    withTable("t") {
      withTempDir { dir =>
        dir.delete()
        Seq((3, 4)).toDF("a", "b")
          .write.format("parquet")
          .save(dir.toString)
        val ex = intercept[AnalysisException](spark.sql(
          s"""
             |CREATE TABLE t
             |USING delta
             |LOCATION '${dir.toURI}'
             |AS SELECT 1 as a, 2 as b
             """.stripMargin))
        assert(ex.getMessage.contains("Cannot create table"))
      }
    }
  }

  testQuietly("CTAS with unknown table properties should throw an error") {
    withTempDir { tempDir =>
      withTable("delta_test") {
        val ex = intercept[AnalysisException] {
          sql(
            s"""
               |CREATE TABLE delta_test
               |USING delta
               |LOCATION '${tempDir.getCanonicalPath}'
               |TBLPROPERTIES('delta.key' = 'value')
               |AS SELECT 3 as a, 4 as b, 1 as c, 2 as d
            """.stripMargin)
        }
        assert(ex.getMessage.contains(
          "Unknown configuration was specified: delta.key"))
      }
    }
  }

  testQuietly("CTAS with invalid table properties should throw an error") {
    withTempDir { tempDir =>
      withTable("delta_test") {
        val ex1 = intercept[IllegalArgumentException] {
          sql(
            s"""
               |CREATE TABLE delta_test
               |USING delta
               |LOCATION '${tempDir.getCanonicalPath}'
               |TBLPROPERTIES('delta.randomPrefixLength' = '-1')
               |AS SELECT 3 as a, 4 as b, 1 as c, 2 as d
            """.stripMargin)
        }
        assert(ex1.getMessage.contains(
          "randomPrefixLength needs to be greater than 0."))

        val ex2 = intercept[IllegalArgumentException] {
          sql(
            s"""
               |CREATE TABLE delta_test
               |USING delta
               |LOCATION '${tempDir.getCanonicalPath}'
               |TBLPROPERTIES('delta.randomPrefixLength' = 'value')
               |AS SELECT 3 as a, 4 as b, 1 as c, 2 as d
            """.stripMargin)
        }
        assert(ex2.getMessage.contains(
          "randomPrefixLength needs to be greater than 0."))
      }
    }
  }

  Seq("a:b", "a%b").foreach { specialChars =>
    test(s"data source table:partition column name containing $specialChars") {
      // On Windows, it looks colon in the file name is illegal by default. See
      // https://support.microsoft.com/en-us/help/289627
      // assume(!Utils.isWindows || specialChars != "a:b")

      withTable("t") {
        withTempDir { dir =>
          spark.sql(
            s"""
               |CREATE TABLE t(a string, `$specialChars` string)
               |USING delta
               |PARTITIONED BY(`$specialChars`)
               |LOCATION '${dir.toURI}'
             """.stripMargin)

          assert(dir.listFiles().forall(_.toString.contains("_delta_log")))
          spark.sql(s"INSERT INTO TABLE t SELECT 1, 2")
          val partEscaped = s"${ExternalCatalogUtils.escapePathName(specialChars)}=2"
          val partFile = new File(dir, partEscaped)
          assert(partFile.listFiles().nonEmpty)
          checkAnswer(spark.table("t"), Row("1", "2") :: Nil)
        }
      }
    }
  }

  Seq("a b", "a:b", "a%b").foreach { specialChars =>
    test(s"location uri contains $specialChars for datasource table") {
      // On Windows, it looks colon in the file name is illegal by default. See
      // https://support.microsoft.com/en-us/help/289627
      // assume(!Utils.isWindows || specialChars != "a:b")

      withTable("t", "t1") {
        withTempDir { dir =>
          val loc = new File(dir, specialChars)
          loc.mkdir()
          // The parser does not recognize the backslashes on Windows as they are.
          // These currently should be escaped.
          val escapedLoc = loc.getAbsolutePath.replace("\\", "\\\\")
          spark.sql(
            s"""
               |CREATE TABLE t(a string)
               |USING delta
               |LOCATION '$escapedLoc'
             """.stripMargin)

          val table = spark.sessionState.catalog.getTableMetadata(TableIdentifier("t"))
          assert(table.location == makeQualifiedPath(loc.getAbsolutePath))
          assert(new Path(table.location).toString.contains(specialChars))

          assert(loc.listFiles().forall(_.toString.contains("_delta_log")))
          spark.sql("INSERT INTO TABLE t SELECT 1")
          assert(!loc.listFiles().forall(_.toString.contains("_delta_log")))
          checkAnswer(spark.table("t"), Row("1") :: Nil)
        }

        withTempDir { dir =>
          val loc = new File(dir, specialChars)
          loc.mkdir()
          // The parser does not recognize the backslashes on Windows as they are.
          // These currently should be escaped.
          val escapedLoc = loc.getAbsolutePath.replace("\\", "\\\\")
          spark.sql(
            s"""
               |CREATE TABLE t1(a string, b string)
               |USING delta
               |PARTITIONED BY(b)
               |LOCATION '$escapedLoc'
             """.stripMargin)

          val table = spark.sessionState.catalog.getTableMetadata(TableIdentifier("t1"))
          assert(table.location == makeQualifiedPath(loc.getAbsolutePath))
          assert(new Path(table.location).toString.contains(specialChars))

          assert(loc.listFiles().forall(_.toString.contains("_delta_log")))
          spark.sql("INSERT INTO TABLE t1 SELECT 1, 2")
          val partFile = new File(loc, "b=2")
          assert(!partFile.listFiles().forall(_.toString.contains("_delta_log")))
          checkAnswer(spark.table("t1"), Row("1", "2") :: Nil)

          spark.sql("INSERT INTO TABLE t1 SELECT 1, '2017-03-03 12:13%3A14'")
          val partFile1 = new File(loc, "b=2017-03-03 12:13%3A14")
          assert(!partFile1.exists())

          if (!Utils.isWindows) {
            // Actual path becomes "b=2017-03-03%2012%3A13%253A14" on Windows.
            val partFile2 = new File(loc, "b=2017-03-03 12%3A13%253A14")
            assert(!partFile2.listFiles().forall(_.toString.contains("_delta_log")))
            checkAnswer(
              spark.table("t1"), Row("1", "2") :: Row("1", "2017-03-03 12:13%3A14") :: Nil)
          }
        }
      }
    }
  }

  test("the qualified path of a delta table is stored in the catalog") {
    withTempDir { dir =>
      withTable("t", "t1") {
        assert(!dir.getAbsolutePath.startsWith("file:/"))
        // The parser does not recognize the backslashes on Windows as they are.
        // These currently should be escaped.
        val escapedDir = dir.getAbsolutePath.replace("\\", "\\\\")
        spark.sql(
          s"""
             |CREATE TABLE t(a string)
             |USING delta
             |LOCATION '$escapedDir'
           """.stripMargin)
        val table = spark.sessionState.catalog.getTableMetadata(TableIdentifier("t"))
        assert(table.location.toString.startsWith("file:/"))
      }
    }

    withTempDir { dir =>
      withTable("t", "t1") {
        assert(!dir.getAbsolutePath.startsWith("file:/"))
        // The parser does not recognize the backslashes on Windows as they are.
        // These currently should be escaped.
        val escapedDir = dir.getAbsolutePath.replace("\\", "\\\\")
        spark.sql(
          s"""
             |CREATE TABLE t1(a string, b string)
             |USING delta
             |PARTITIONED BY(b)
             |LOCATION '$escapedDir'
           """.stripMargin)
        val table = spark.sessionState.catalog.getTableMetadata(TableIdentifier("t1"))
        assert(table.location.toString.startsWith("file:/"))
      }
    }
  }

  testQuietly("CREATE TABLE with existing data path") {
    withTempPath { path =>
      withTable("src", "t1", "t2", "t3", "t4", "t5", "t6") {
        sql("CREATE TABLE src(i int, p string) USING delta PARTITIONED BY (p) " +
          "TBLPROPERTIES('delta.randomizeFilePrefixes' = 'true') " +
          s"LOCATION '${path.getAbsolutePath}'")
        sql("INSERT INTO src SELECT 1, 'a'")

        // CREATE TABLE without specifying anything works
        sql(s"CREATE TABLE t1 USING delta LOCATION '${path.getAbsolutePath}'")
        checkAnswer(spark.table("t1"), Row(1, "a"))

        // CREATE TABLE with the same schema and partitioning but no properties works
        sql(s"CREATE TABLE t2(i int, p string) USING delta PARTITIONED BY (p) " +
          s"LOCATION '${path.getAbsolutePath}'")
        checkAnswer(spark.table("t2"), Row(1, "a"))
        // Table properties should not be changed to empty.
        assert(getTableProperties("t2") == Map("delta.randomizeFilePrefixes" -> "true"))

        // CREATE TABLE with the same schema but no partitioning fails.
        val e0 = intercept[AnalysisException] {
          sql(s"CREATE TABLE t3(i int, p string) USING delta LOCATION '${path.getAbsolutePath}'")
        }
        assert(e0.message.contains("The specified partitioning does not match the existing"))

        // CREATE TABLE with different schema fails
        val e1 = intercept[AnalysisException] {
          sql(s"CREATE TABLE t4(j int, p string) USING delta LOCATION '${path.getAbsolutePath}'")
        }
        assert(e1.message.contains("The specified schema does not match the existing"))

        // CREATE TABLE with different partitioning fails
        val e2 = intercept[AnalysisException] {
          sql(s"CREATE TABLE t5(i int, p string) USING delta PARTITIONED BY (i) " +
            s"LOCATION '${path.getAbsolutePath}'")
        }
        assert(e2.message.contains("The specified partitioning does not match the existing"))

        // CREATE TABLE with different table properties fails
        val e3 = intercept[AnalysisException] {
          sql(s"CREATE TABLE t6 USING delta " +
            "TBLPROPERTIES ('delta.randomizeFilePrefixes' = 'false') " +
            s"LOCATION '${path.getAbsolutePath}'")
        }
        assert(e3.message.contains("The specified properties do not match the existing"))
      }
    }
  }

  test("CREATE TABLE on existing data should not commit metadata") {
    withTempDir { tempDir =>
      val path = tempDir.getCanonicalPath()
      val df = Seq(1, 2, 3, 4, 5).toDF()
      df.write.format("delta").save(path)
      val deltaLog = getDeltaLog(new Path(path))

      val oldVersion = deltaLog.snapshot.version
      sql(s"CREATE TABLE table USING delta LOCATION '$path'")
      assert(oldVersion == deltaLog.snapshot.version)
    }
  }
}

class DeltaTableCreationSuite
  extends DeltaTableCreationTests
  with DeltaSQLCommandTest {

  private def loadTable(tableName: String): Table = {
    val ti = spark.sessionState.sqlParser.parseMultipartIdentifier(tableName)
    val namespace = if (ti.length == 1) Array("default") else ti.init.toArray
    spark.sessionState.catalogManager.currentCatalog.asInstanceOf[TableCatalog]
      .loadTable(Identifier.of(namespace, ti.last))
  }

  override protected def getPartitioningColumns(tableName: String): Seq[String] = {
    loadTable(tableName).partitioning()
      .map(_.references().head.fieldNames().mkString("."))
  }

  override def getSchema(tableName: String): StructType = {
    loadTable(tableName).schema()
  }

  override protected def getTableProperties(tableName: String): Map[String, String] = {
    loadTable(tableName).properties().asScala.toMap
      .filterKeys(!CatalogV2Util.TABLE_RESERVED_PROPERTIES.contains(_))
  }

  testQuietly("REPLACE TABLE") {
    withTempDir { dir =>
      withTable("delta_test") {
        sql(
          s"""CREATE TABLE delta_test
             |USING delta
             |LOCATION '${dir.getAbsolutePath}'
             |AS SELECT 1 as a
          """.stripMargin)
        val deltaLog = DeltaLog.forTable(spark, dir)
        assert(deltaLog.snapshot.version === 0, "CTAS should be a single commit")

        sql(
          s"""REPLACE TABLE delta_test (col string)
             |USING delta
             |LOCATION '${dir.getAbsolutePath}'
          """.stripMargin)
        assert(deltaLog.snapshot.version === 1)
        assert(deltaLog.snapshot.schema === new StructType().add("col", "string"))

        val e = intercept[IllegalArgumentException] {
          sql(
            s"""REPLACE TABLE delta_test (col2 string)
               |USING delta
               |LOCATION '${dir.getAbsolutePath}'
               |OPTIONS (overwriteSchema = 'false')
          """.stripMargin)
        }
        assert(e.getMessage.contains("overwriteSchema is not allowed"))

        val e2 = intercept[AnalysisException] {
          sql(
            s"""REPLACE TABLE delta_test
               |USING delta
               |LOCATION '${dir.getAbsolutePath}'
          """.stripMargin)
        }
        assert(e2.getMessage.contains("schema is not provided"))
      }
    }
  }

  testQuietly("CREATE OR REPLACE TABLE on table without schema") {
    withTempDir { dir =>
      withTable("delta_test") {
        spark.range(10).write.format("delta").option("path", dir.getCanonicalPath)
          .saveAsTable("delta_test")
        // We need the schema
        val e = intercept[AnalysisException] {
          sql(s"""CREATE OR REPLACE TABLE delta_test
                 |USING delta
                 |LOCATION '${dir.getAbsolutePath}'
               """.stripMargin)
        }
        assert(e.getMessage.contains("schema is not provided"))
      }
    }
  }

  testQuietly("CREATE OR REPLACE TABLE on non-empty directory") {
    withTempDir { dir =>
      spark.range(10).write.format("delta").save(dir.getCanonicalPath)
      withTable("delta_test") {
        // We need the schema
        val e = intercept[AnalysisException] {
          sql(s"""CREATE OR REPLACE TABLE delta_test
                 |USING delta
                 |LOCATION '${dir.getAbsolutePath}'
               """.stripMargin)
        }
        assert(e.getMessage.contains("schema is not provided"))
      }
    }
  }

  testQuietly("REPLACE TABLE on non-empty directory") {
    withTempDir { dir =>
      spark.range(10).write.format("delta").save(dir.getCanonicalPath)
      withTable("delta_test") {
        val e = intercept[AnalysisException] {
          sql(
            s"""REPLACE TABLE delta_test
               |USING delta
               |LOCATION '${dir.getAbsolutePath}'
           """.stripMargin)
        }
        assert(e.getMessage.contains("cannot be replaced as it did not exist"))
      }
    }
  }
}
