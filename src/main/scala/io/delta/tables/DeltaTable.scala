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

package io.delta.tables

import scala.collection.JavaConverters._

import org.apache.spark.sql.delta._
import org.apache.spark.sql.delta.actions.Protocol
import org.apache.spark.sql.delta.catalog.DeltaTableV2
import io.delta.tables.execution._
import org.apache.hadoop.fs.Path

import org.apache.spark.annotation._
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.types.StructType

/**
 * :: Evolving ::
 *
 * Main class for programmatically interacting with Delta tables.
 * You can create DeltaTable instances using the static methods.
 * {{{
 *   DeltaTable.forPath(sparkSession, pathToTheDeltaTable)
 * }}}
 *
 * @since 0.3.0
 */
@Evolving
class DeltaTable private[tables](
    @transient private val _df: Dataset[Row],
    @transient private val table: DeltaTableV2)
  extends DeltaTableOperations with Serializable {

  protected def deltaLog: DeltaLog = {
    /** Assert the codes run in the driver. */
    if (table == null) {
      throw new IllegalStateException("DeltaTable cannot be used in executors")
    }

    table.deltaLog
  }

  protected def df: Dataset[Row] = {
    /** Assert the codes run in the driver. */
    if (_df == null) {
      throw new IllegalStateException("DeltaTable cannot be used in executors")
    }

    _df
  }

  /**
   * :: Evolving ::
   *
   * Apply an alias to the DeltaTable. This is similar to `Dataset.as(alias)` or
   * SQL `tableName AS alias`.
   *
   * @since 0.3.0
   */
  @Evolving
  def as(alias: String): DeltaTable = new DeltaTable(df.as(alias), table)

  /**
   * :: Evolving ::
   *
   * Apply an alias to the DeltaTable. This is similar to `Dataset.as(alias)` or
   * SQL `tableName AS alias`.
   *
   * @since 0.3.0
   */
  @Evolving
  def alias(alias: String): DeltaTable = as(alias)

  /**
   * :: Evolving ::
   *
   * Get a DataFrame (that is, Dataset[Row]) representation of this Delta table.
   *
   * @since 0.3.0
   */
  @Evolving
  def toDF: Dataset[Row] = df

  /**
   * :: Evolving ::
   *
   * Recursively delete files and directories in the table that are not needed by the table for
   * maintaining older versions up to the given retention threshold. This method will return an
   * empty DataFrame on successful completion.
   *
   * @param retentionHours The retention threshold in hours. Files required by the table for
   *                       reading versions earlier than this will be preserved and the
   *                       rest of them will be deleted.
   * @since 0.3.0
   */
  @Evolving
  def vacuum(retentionHours: Double): DataFrame = {
    executeVacuum(deltaLog, Some(retentionHours))
  }

  /**
   * :: Evolving ::
   *
   * Recursively delete files and directories in the table that are not needed by the table for
   * maintaining older versions up to the given retention threshold. This method will return an
   * empty DataFrame on successful completion.
   *
   * note: This will use the default retention period of 7 days.
   *
   * @since 0.3.0
   */
  @Evolving
  def vacuum(): DataFrame = {
    executeVacuum(deltaLog, None)
  }

  /**
   * :: Evolving ::
   *
   * Get the information of the latest `limit` commits on this table as a Spark DataFrame.
   * The information is in reverse chronological order.
   *
   * @param limit The number of previous commands to get history for
   *
   * @since 0.3.0
   */
  @Evolving
  def history(limit: Int): DataFrame = {
    executeHistory(deltaLog, Some(limit))
  }

  /**
   * :: Evolving ::
   *
   * Get the information available commits on this table as a Spark DataFrame.
   * The information is in reverse chronological order.
   *
   * @since 0.3.0
   */
  @Evolving
  def history(): DataFrame = {
    executeHistory(deltaLog, None)
  }

  /**
   * :: Evolving ::
   *
   * Generate a manifest for the given Delta Table
   *
   * @param mode Specifies the mode for the generation of the manifest.
   *             The valid modes are as follows (not case sensitive):
   *              - "symlink_format_manifest" : This will generate manifests in symlink format
   *                                            for Presto and Athena read support.
   *             See the online documentation for more information.
   * @since 0.5.0
   */
  @Evolving
  def generate(mode: String): Unit = {
    val path = deltaLog.dataPath.toString
    executeGenerate(s"delta.`$path`", mode)
  }

  /**
   * :: Evolving ::
   *
   * Delete data from the table that match the given `condition`.
   *
   * @param condition Boolean SQL expression
   *
   * @since 0.3.0
   */
  @Evolving
  def delete(condition: String): Unit = {
    delete(functions.expr(condition))
  }

  /**
   * :: Evolving ::
   *
   * Delete data from the table that match the given `condition`.
   *
   * @param condition Boolean SQL expression
   *
   * @since 0.3.0
   */
  @Evolving
  def delete(condition: Column): Unit = {
    executeDelete(Some(condition.expr))
  }

  /**
   * :: Evolving ::
   *
   * Delete data from the table.
   *
   * @since 0.3.0
   */
  @Evolving
  def delete(): Unit = {
    executeDelete(None)
  }


  /**
   * :: Evolving ::
   *
   * Update rows in the table based on the rules defined by `set`.
   *
   * Scala example to increment the column `data`.
   * {{{
   *    import org.apache.spark.sql.functions._
   *
   *    deltaTable.update(Map("data" -> col("data") + 1))
   * }}}
   *
   * @param set rules to update a row as a Scala map between target column names and
   *            corresponding update expressions as Column objects.
   * @since 0.3.0
   */
  @Evolving
  def update(set: Map[String, Column]): Unit = {
    executeUpdate(set, None)
  }

  /**
   * :: Evolving ::
   *
   * Update rows in the table based on the rules defined by `set`.
   *
   * Java example to increment the column `data`.
   * {{{
   *    import org.apache.spark.sql.Column;
   *    import org.apache.spark.sql.functions;
   *
   *    deltaTable.update(
   *      new HashMap<String, Column>() {{
   *        put("data", functions.col("data").plus(1));
   *      }}
   *    );
   * }}}
   *
   * @param set rules to update a row as a Java map between target column names and
   *            corresponding update expressions as Column objects.
   * @since 0.3.0
   */
  @Evolving
  def update(set: java.util.Map[String, Column]): Unit = {
    executeUpdate(set.asScala, None)
  }

  /**
   * :: Evolving ::
   *
   * Update data from the table on the rows that match the given `condition`
   * based on the rules defined by `set`.
   *
   * Scala example to increment the column `data`.
   * {{{
   *    import org.apache.spark.sql.functions._
   *
   *    deltaTable.update(
   *      col("date") > "2018-01-01",
   *      Map("data" -> col("data") + 1))
   * }}}
   *
   * @param condition boolean expression as Column object specifying which rows to update.
   * @param set rules to update a row as a Scala map between target column names and
   *            corresponding update expressions as Column objects.
   * @since 0.3.0
   */
  @Evolving
  def update(condition: Column, set: Map[String, Column]): Unit = {
    executeUpdate(set, Some(condition))
  }

  /**
   * :: Evolving ::
   *
   * Update data from the table on the rows that match the given `condition`
   * based on the rules defined by `set`.
   *
   * Java example to increment the column `data`.
   * {{{
   *    import org.apache.spark.sql.Column;
   *    import org.apache.spark.sql.functions;
   *
   *    deltaTable.update(
   *      functions.col("date").gt("2018-01-01"),
   *      new HashMap<String, Column>() {{
   *        put("data", functions.col("data").plus(1));
   *      }}
   *    );
   * }}}
   *
   * @param condition boolean expression as Column object specifying which rows to update.
   * @param set rules to update a row as a Java map between target column names and
   *            corresponding update expressions as Column objects.
   * @since 0.3.0
   */
  @Evolving
  def update(condition: Column, set: java.util.Map[String, Column]): Unit = {
    executeUpdate(set.asScala, Some(condition))
  }

  /**
   * :: Evolving ::
   *
   * Update rows in the table based on the rules defined by `set`.
   *
   * Scala example to increment the column `data`.
   * {{{
   *    deltaTable.updateExpr(Map("data" -> "data + 1")))
   * }}}
   *
   * @param set rules to update a row as a Scala map between target column names and
   *            corresponding update expressions as SQL formatted strings.
   * @since 0.3.0
   */
  @Evolving
  def updateExpr(set: Map[String, String]): Unit = {
    executeUpdate(toStrColumnMap(set), None)
  }

  /**
   * :: Evolving ::
   *
   * Update rows in the table based on the rules defined by `set`.
   *
   * Java example to increment the column `data`.
   * {{{
   *    deltaTable.updateExpr(
   *      new HashMap<String, String>() {{
   *        put("data", "data + 1");
   *      }}
   *    );
   * }}}
   *
   * @param set rules to update a row as a Java map between target column names and
   *            corresponding update expressions as SQL formatted strings.
   * @since 0.3.0
   */
  @Evolving
  def updateExpr(set: java.util.Map[String, String]): Unit = {
    executeUpdate(toStrColumnMap(set.asScala), None)
  }

  /**
   * :: Evolving ::
   *
   * Update data from the table on the rows that match the given `condition`,
   * which performs the rules defined by `set`.
   *
   * Scala example to increment the column `data`.
   * {{{
   *    deltaTable.update(
   *      "date > '2018-01-01'",
   *      Map("data" -> "data + 1"))
   * }}}
   *
   * @param condition boolean expression as SQL formatted string object specifying
   *                  which rows to update.
   * @param set rules to update a row as a Scala map between target column names and
   *            corresponding update expressions as SQL formatted strings.
   * @since 0.3.0
   */
  @Evolving
  def updateExpr(condition: String, set: Map[String, String]): Unit = {
    executeUpdate(toStrColumnMap(set), Some(functions.expr(condition)))
  }

  /**
   * :: Evolving ::
   *
   * Update data from the table on the rows that match the given `condition`,
   * which performs the rules defined by `set`.
   *
   * Java example to increment the column `data`.
   * {{{
   *    deltaTable.update(
   *      "date > '2018-01-01'",
   *      new HashMap<String, String>() {{
   *        put("data", "data + 1");
   *      }}
   *    );
   * }}}
   *
   * @param condition boolean expression as SQL formatted string object specifying
   *                  which rows to update.
   * @param set rules to update a row as a Java map between target column names and
   *            corresponding update expressions as SQL formatted strings.
   * @since 0.3.0
   */
  @Evolving
  def updateExpr(condition: String, set: java.util.Map[String, String]): Unit = {
    executeUpdate(toStrColumnMap(set.asScala), Some(functions.expr(condition)))
  }

  /**
   * :: Evolving ::
   *
   * Merge data from the `source` DataFrame based on the given merge `condition`. This returns
   * a [[DeltaMergeBuilder]] object that can be used to specify the update, delete, or insert
   * actions to be performed on rows based on whether the rows matched the condition or not.
   *
   * See the [[DeltaMergeBuilder]] for a full description of this operation and what combinations of
   * update, delete and insert operations are allowed.
   *
   * Scala example to update a key-value Delta table with new key-values from a source DataFrame:
   * {{{
   *    deltaTable
   *     .as("target")
   *     .merge(
   *       source.as("source"),
   *       "target.key = source.key")
   *     .whenMatched
   *     .updateExpr(Map(
   *       "value" -> "source.value"))
   *     .whenNotMatched
   *     .insertExpr(Map(
   *       "key" -> "source.key",
   *       "value" -> "source.value"))
   *     .execute()
   * }}}
   *
   * Java example to update a key-value Delta table with new key-values from a source DataFrame:
   * {{{
   *    deltaTable
   *     .as("target")
   *     .merge(
   *       source.as("source"),
   *       "target.key = source.key")
   *     .whenMatched
   *     .updateExpr(
   *        new HashMap<String, String>() {{
   *          put("value" -> "source.value");
   *        }})
   *     .whenNotMatched
   *     .insertExpr(
   *        new HashMap<String, String>() {{
   *         put("key", "source.key");
   *         put("value", "source.value");
   *       }})
   *     .execute();
   * }}}
   *
   * @param source source Dataframe to be merged.
   * @param condition boolean expression as SQL formatted string
   * @since 0.3.0
   */
  @Evolving
  def merge(source: DataFrame, condition: String): DeltaMergeBuilder = {
    merge(source, functions.expr(condition))
  }

  /**
   * :: Evolving ::
   *
   * Merge data from the `source` DataFrame based on the given merge `condition`. This returns
   * a [[DeltaMergeBuilder]] object that can be used to specify the update, delete, or insert
   * actions to be performed on rows based on whether the rows matched the condition or not.
   *
   * See the [[DeltaMergeBuilder]] for a full description of this operation and what combinations of
   * update, delete and insert operations are allowed.
   *
   * Scala example to update a key-value Delta table with new key-values from a source DataFrame:
   * {{{
   *    deltaTable
   *     .as("target")
   *     .merge(
   *       source.as("source"),
   *       "target.key = source.key")
   *     .whenMatched
   *     .updateExpr(Map(
   *       "value" -> "source.value"))
   *     .whenNotMatched
   *     .insertExpr(Map(
   *       "key" -> "source.key",
   *       "value" -> "source.value"))
   *     .execute()
   * }}}
   *
   * Java example to update a key-value Delta table with new key-values from a source DataFrame:
   * {{{
   *    deltaTable
   *     .as("target")
   *     .merge(
   *       source.as("source"),
   *       "target.key = source.key")
   *     .whenMatched
   *     .updateExpr(
   *        new HashMap<String, String>() {{
   *          put("value" -> "source.value")
   *        }})
   *     .whenNotMatched
   *     .insertExpr(
   *        new HashMap<String, String>() {{
   *         put("key", "source.key");
   *         put("value", "source.value");
   *       }})
   *     .execute()
   * }}}
   *
   * @param source source Dataframe to be merged.
   * @param condition boolean expression as a Column object
   * @since 0.3.0
   */
  @Evolving
  def merge(source: DataFrame, condition: Column): DeltaMergeBuilder = {
    DeltaMergeBuilder(this, source, condition)
  }

  /**
   * :: Evolving ::
   *
   * Updates the protocol version of the table to leverage new features. Upgrading the reader
   * version will prevent all clients that have an older version of Delta Lake from accessing this
   * table. Upgrading the writer version will prevent older versions of Delta Lake to write to this
   * table. The reader or writer version cannot be downgraded.
   *
   * See online documentation and Delta's protocol specification at PROTOCOL.md for more details.
   *
   * @since 0.8.0
   */
  @Evolving
  def upgradeTableProtocol(readerVersion: Int, writerVersion: Int): Unit = {
    deltaLog.upgradeProtocol(Protocol(readerVersion, writerVersion))
  }
}

/**
 * :: Evolving ::
 *
 * Companion object to create DeltaTable instances.
 *
 * {{{
 *   DeltaTable.forPath(sparkSession, pathToTheDeltaTable)
 * }}}
 *
 * @since 0.3.0
 */
object DeltaTable {

  /**
   * :: Evolving ::
   *
   * Create a DeltaTable from the given parquet table and partition schema.
   * Takes an existing parquet table and constructs a delta transaction log in the base path of
   * that table.
   *
   * Note: Any changes to the table during the conversion process may not result in a consistent
   * state at the end of the conversion. Users should stop any changes to the table before the
   * conversion is started.
   *
   * An example usage would be
   * {{{
   *  io.delta.tables.DeltaTable.convertToDelta(
   *   spark,
   *   "parquet.`/path`",
   *   new StructType().add(StructField("key1", LongType)).add(StructField("key2", StringType)))
   * }}}
   *
   * @since 0.4.0
   */
  @Evolving
  def convertToDelta(
      spark: SparkSession,
      identifier: String,
      partitionSchema: StructType): DeltaTable = {
    val tableId: TableIdentifier = spark.sessionState.sqlParser.parseTableIdentifier(identifier)
    DeltaConvert.executeConvert(spark, tableId, Some(partitionSchema), None)
  }

  /**
   * :: Evolving ::
   *
   * Create a DeltaTable from the given parquet table and partition schema.
   * Takes an existing parquet table and constructs a delta transaction log in the base path of
   * that table.
   *
   * Note: Any changes to the table during the conversion process may not result in a consistent
   * state at the end of the conversion. Users should stop any changes to the table before the
   * conversion is started.
   *
   * An example usage would be
   * {{{
   *  io.delta.tables.DeltaTable.convertToDelta(
   *   spark,
   *   "parquet.`/path`",
   *   "key1 long, key2 string")
   * }}}
   *
   * @since 0.4.0
   */
  @Evolving
  def convertToDelta(
      spark: SparkSession,
      identifier: String,
      partitionSchema: String): DeltaTable = {
    val tableId: TableIdentifier = spark.sessionState.sqlParser.parseTableIdentifier(identifier)
    DeltaConvert.executeConvert(spark, tableId, Some(StructType.fromDDL(partitionSchema)), None)
  }

  /**
   * :: Evolving ::
   *
   * Create a DeltaTable from the given parquet table. Takes an existing parquet table and
   * constructs a delta transaction log in the base path of the table.
   *
   * Note: Any changes to the table during the conversion process may not result in a consistent
   * state at the end of the conversion. Users should stop any changes to the table before the
   * conversion is started.
   *
   * An Example would be
   * {{{
   *  io.delta.tables.DeltaTable.convertToDelta(
   *   spark,
   *   "parquet.`/path`"
   * }}}
   *
   * @since 0.4.0
   */
  @Evolving
  def convertToDelta(
      spark: SparkSession,
      identifier: String): DeltaTable = {
    val tableId: TableIdentifier = spark.sessionState.sqlParser.parseTableIdentifier(identifier)
    DeltaConvert.executeConvert(spark, tableId, None, None)
  }

  /**
   * :: Evolving ::
   *
   * Create a DeltaTable for the data at the given `path`.
   *
   * Note: This uses the active SparkSession in the current thread to read the table data. Hence,
   * this throws error if active SparkSession has not been set, that is,
   * `SparkSession.getActiveSession()` is empty.
   *
   * @since 0.3.0
   */
  @Evolving
  def forPath(path: String): DeltaTable = {
    val sparkSession = SparkSession.getActiveSession.getOrElse {
      throw new IllegalArgumentException("Could not find active SparkSession")
    }
    forPath(sparkSession, path)
  }

  /**
   * :: Evolving ::
   *
   * Create a DeltaTable for the data at the given `path` using the given SparkSession.
   *
   * @since 0.3.0
   */
  @Evolving
  def forPath(sparkSession: SparkSession, path: String): DeltaTable = {
    val hdpPath = new Path(path)
    if (DeltaTableUtils.isDeltaTable(sparkSession, hdpPath)) {
      new DeltaTable(sparkSession.read.format("delta").load(path),
        DeltaTableV2(sparkSession, hdpPath))
    } else {
      throw DeltaErrors.notADeltaTableException(DeltaTableIdentifier(path = Some(path)))
    }
  }

  /**
   * Create a DeltaTable using the given table or view name using the given SparkSession.
   *
   * Note: This uses the active SparkSession in the current thread to read the table data. Hence,
   * this throws error if active SparkSession has not been set, that is,
   * `SparkSession.getActiveSession()` is empty.
   */
  def forName(tableOrViewName: String): DeltaTable = {
    val sparkSession = SparkSession.getActiveSession.getOrElse {
      throw new IllegalArgumentException("Could not find active SparkSession")
    }
    forName(sparkSession, tableOrViewName)
  }

  /**
   * Create a DeltaTable using the given table or view name using the given SparkSession.
   */
  def forName(sparkSession: SparkSession, tableName: String): DeltaTable = {
    val tableId = sparkSession.sessionState.sqlParser.parseTableIdentifier(tableName)
    if (DeltaTableUtils.isDeltaTable(sparkSession, tableId)) {
      val tbl = sparkSession.sessionState.catalog.getTableMetadata(tableId)
      new DeltaTable(
        sparkSession.table(tableName),
        DeltaTableV2(sparkSession, new Path(tbl.location), Some(tbl), Some(tableName)))
    } else {
      throw DeltaErrors.notADeltaTableException(DeltaTableIdentifier(table = Some(tableId)))
    }
  }

  /**
   * :: Evolving ::
   *
   * Check if the provided `identifier` string, in this case a file path,
   * is the root of a Delta table using the given SparkSession.
   *
   * An example would be
   * {{{
   *   DeltaTable.isDeltaTable(spark, "path/to/table")
   * }}}
   *
   * @since 0.4.0
   */
  @Evolving
  def isDeltaTable(sparkSession: SparkSession, identifier: String): Boolean = {
    DeltaTableUtils.isDeltaTable(sparkSession, new Path(identifier))
  }

  /**
   * :: Evolving ::
   *
   * Check if the provided `identifier` string, in this case a file path,
   * is the root of a Delta table.
   *
   * Note: This uses the active SparkSession in the current thread to search for the table. Hence,
   * this throws error if active SparkSession has not been set, that is,
   * `SparkSession.getActiveSession()` is empty.
   *
   * An example would be
   * {{{
   *   DeltaTable.isDeltaTable(spark, "/path/to/table")
   * }}}
   *
   * @since 0.4.0
   */
  @Evolving
  def isDeltaTable(identifier: String): Boolean = {
    val sparkSession = SparkSession.getActiveSession.getOrElse {
      throw new IllegalArgumentException("Could not find active SparkSession")
    }
    isDeltaTable(sparkSession, identifier)
  }
}
