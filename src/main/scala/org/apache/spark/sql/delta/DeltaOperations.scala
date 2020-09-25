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

import org.apache.spark.sql.delta.actions.{Metadata, Protocol}
import org.apache.spark.sql.delta.util.JsonUtils

import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.sql.catalyst.plans.logical.DeltaMergeIntoClause
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types.{StructField, StructType}

/**
 * Exhaustive list of operations that can be performed on a Delta table. These operations are
 * tracked as the first line in delta logs, and power `DESCRIBE HISTORY` for Delta tables.
 */
object DeltaOperations {

  /**
   * An operation that can be performed on a Delta table.
   * @param name The name of the operation.
   */
  sealed abstract class Operation(val name: String) {
    val parameters: Map[String, Any]

    lazy val jsonEncodedValues: Map[String, String] = parameters.mapValues(JsonUtils.toJson(_))

    val operationMetrics: Set[String] = Set()

    def transformMetrics(metrics: Map[String, SQLMetric]): Map[String, String] = {
      metrics.filterKeys( s =>
        operationMetrics.contains(s)
      ).transform((_, v) => v.value.toString)
    }

    val userMetadata: Option[String] = None
  }

  /** Recorded during batch inserts. Predicates can be provided for overwrites. */
  case class Write(
      mode: SaveMode,
      partitionBy: Option[Seq[String]] = None,
      predicate: Option[String] = None,
      override val userMetadata: Option[String] = None) extends Operation("WRITE") {
    override val parameters: Map[String, Any] = Map("mode" -> mode.name()) ++
      partitionBy.map("partitionBy" -> JsonUtils.toJson(_)) ++
      predicate.map("predicate" -> _)

    override val operationMetrics: Set[String] = DeltaOperationMetrics.WRITE
  }
  /** Recorded during streaming inserts. */
  case class StreamingUpdate(
      outputMode: OutputMode,
      queryId: String,
      epochId: Long,
      override val userMetadata: Option[String] = None) extends Operation("STREAMING UPDATE") {
    override val parameters: Map[String, Any] =
      Map("outputMode" -> outputMode.toString, "queryId" -> queryId, "epochId" -> epochId.toString)
    override val operationMetrics: Set[String] = DeltaOperationMetrics.STREAMING_UPDATE
  }
  /** Recorded while deleting certain partitions. */
  case class Delete(predicate: Seq[String]) extends Operation("DELETE") {
    override val parameters: Map[String, Any] = Map("predicate" -> JsonUtils.toJson(predicate))
    override val operationMetrics: Set[String] = DeltaOperationMetrics.DELETE

    override def transformMetrics(metrics: Map[String, SQLMetric]): Map[String, String] = {
      var strMetrics = super.transformMetrics(metrics)
      if (metrics.contains("numOutputRows")) {
        strMetrics += "numCopiedRows" -> metrics("numOutputRows").value.toString
      }
      // find the case where deletedRows are not captured
      if (strMetrics("numDeletedRows") == "0" && strMetrics("numRemovedFiles") != "0") {
        // identify when row level metrics are unavailable. This will happen when the entire
        // table or partition are deleted.
        strMetrics -= "numDeletedRows"
        strMetrics -= "numCopiedRows"
        strMetrics -= "numAddedFiles"
      }
      strMetrics
    }
  }
  /** Recorded when truncating the table. */
  case class Truncate() extends Operation("TRUNCATE") {
    override val parameters: Map[String, Any] = Map.empty
    override val operationMetrics: Set[String] = DeltaOperationMetrics.TRUNCATE
  }
  /** Recorded when fscking the table. */
  case class Fsck(numRemovedFiles: Long) extends Operation("FSCK") {
    override val parameters: Map[String, Any] = Map(
      "numRemovedFiles" -> numRemovedFiles
    )
    override val operationMetrics: Set[String] = DeltaOperationMetrics.FSCK
  }
  /** Recorded when converting a table into a Delta table. */
  case class Convert(
      numFiles: Long,
      partitionBy: Seq[String],
      collectStats: Boolean,
      catalogTable: Option[String]) extends Operation("CONVERT") {
    override val parameters: Map[String, Any] = Map(
      "numFiles" -> numFiles,
      "partitionedBy" -> JsonUtils.toJson(partitionBy),
      "collectStats" -> collectStats) ++ catalogTable.map("catalogTable" -> _)
    override val operationMetrics: Set[String] = DeltaOperationMetrics.CONVERT
  }
  /** Recorded when optimizing the table. */
  case class Optimize(
      predicate: Seq[String],
      zOrderBy: Seq[String],
      batchId: Int,
      auto: Boolean) extends Operation("OPTIMIZE") {
    override val parameters: Map[String, Any] = Map(
      "predicate" -> JsonUtils.toJson(predicate),
      "zOrderBy" -> JsonUtils.toJson(zOrderBy),
      "batchId" -> JsonUtils.toJson(batchId),
      "auto" -> auto
    )
    override val operationMetrics: Set[String] = DeltaOperationMetrics.OPTIMIZE
  }

  /** Represents the predicates and action type (insert, update, delete) for a Merge clause */
  case class MergePredicate(
      predicate: Option[String],
      actionType: String)

  object MergePredicate {
    def apply(mergeClause: DeltaMergeIntoClause): MergePredicate = {
      MergePredicate(
        predicate = mergeClause.condition.map(_.sql),
        mergeClause.clauseType.toLowerCase())
    }
  }

  /**
   * Recorded when a merge operation is committed to the table.
   *
   * `updatePredicate`, `deletePredicate`, and `insertPredicate` are DEPRECATED.
   * Only use `predicate`, `matchedPredicates`, and `notMatchedPredicates` to record the merge.
   */
  case class Merge(
      predicate: Option[String],
      updatePredicate: Option[String],
      deletePredicate: Option[String],
      insertPredicate: Option[String],
      matchedPredicates: Seq[MergePredicate],
      notMatchedPredicates: Seq[MergePredicate]) extends Operation("MERGE") {

    override val parameters: Map[String, Any] = {
      predicate.map("predicate" -> _).toMap ++
        updatePredicate.map("updatePredicate" -> _).toMap ++
        deletePredicate.map("deletePredicate" -> _).toMap ++
        insertPredicate.map("insertPredicate" -> _).toMap +
        ("matchedPredicates" -> JsonUtils.toJson(matchedPredicates)) +
        ("notMatchedPredicates" -> JsonUtils.toJson(notMatchedPredicates))
    }
    override val operationMetrics: Set[String] = DeltaOperationMetrics.MERGE
  }

  object Merge {
    /** constructor to provide default values for deprecated fields */
    def apply(
        predicate: Option[String],
        matchedPredicates: Seq[MergePredicate],
        notMatchedPredicates: Seq[MergePredicate]): Merge = Merge(
          predicate,
          updatePredicate = None,
          deletePredicate = None,
          insertPredicate = None,
          matchedPredicates,
          notMatchedPredicates)
  }

  /** Recorded when an update operation is committed to the table. */
  case class Update(predicate: Option[String]) extends Operation("UPDATE") {
    override val parameters: Map[String, Any] = predicate.map("predicate" -> _).toMap
    override val operationMetrics: Set[String] = DeltaOperationMetrics.UPDATE

    override def transformMetrics(metrics: Map[String, SQLMetric]): Map[String, String] = {
      val numOutputRows = metrics("numOutputRows").value
      val numUpdatedRows = metrics("numUpdatedRows").value
      var strMetrics = super.transformMetrics(metrics)
      // In the case where the numUpdatedRows is not captured in the UpdateCommand implementation
      // we can siphon out the metrics from the BasicWriteStatsTracker for that command.
      // This is for the case where the entire partition is re-written.
      if (numUpdatedRows == 0 && numOutputRows != 0) {
        strMetrics += "numUpdatedRows" -> numOutputRows.toString
        strMetrics += "numCopiedRows" -> "0"
      } else {
        strMetrics += "numCopiedRows" -> (
          numOutputRows - strMetrics("numUpdatedRows").toLong).toString
      }
      strMetrics
    }
  }
  /** Recorded when the table is created. */
  case class CreateTable(metadata: Metadata, isManaged: Boolean, asSelect: Boolean = false)
      extends Operation("CREATE TABLE" + s"${if (asSelect) " AS SELECT" else ""}") {
    override val parameters: Map[String, Any] = Map(
      "isManaged" -> isManaged.toString,
      "description" -> Option(metadata.description),
      "partitionBy" -> JsonUtils.toJson(metadata.partitionColumns),
      "properties" -> JsonUtils.toJson(metadata.configuration))
    override val operationMetrics: Set[String] = if (!asSelect) {
      Set()
    } else {
      DeltaOperationMetrics.WRITE
    }
  }
  /** Recorded when the table is replaced. */
  case class ReplaceTable(
      metadata: Metadata,
      isManaged: Boolean,
      orCreate: Boolean,
      asSelect: Boolean = false)
    extends Operation(s"${if (orCreate) "CREATE OR " else ""}REPLACE TABLE" +
      s"${if (asSelect) " AS SELECT" else ""}") {
    override val parameters: Map[String, Any] = Map(
      "isManaged" -> isManaged.toString,
      "description" -> Option(metadata.description),
      "partitionBy" -> JsonUtils.toJson(metadata.partitionColumns),
      "properties" -> JsonUtils.toJson(metadata.configuration))
    override val operationMetrics: Set[String] = if (!asSelect) {
      Set()
    } else {
      DeltaOperationMetrics.WRITE
    }
  }
  /** Recorded when the table properties are set. */
  case class SetTableProperties(
      properties: Map[String, String]) extends Operation("SET TBLPROPERTIES") {
    override val parameters: Map[String, Any] = Map("properties" -> JsonUtils.toJson(properties))
  }
  /** Recorded when the table properties are unset. */
  case class UnsetTableProperties(
      propKeys: Seq[String],
      ifExists: Boolean) extends Operation("UNSET TBLPROPERTIES") {
    override val parameters: Map[String, Any] = Map(
      "properties" -> JsonUtils.toJson(propKeys),
      "ifExists" -> ifExists)
  }
  /** Recorded when columns are added. */
  case class AddColumns(
      colsToAdd: Seq[QualifiedColTypeWithPositionForLog]) extends Operation("ADD COLUMNS") {

    override val parameters: Map[String, Any] = Map(
      "columns" -> JsonUtils.toJson(colsToAdd.map {
        case QualifiedColTypeWithPositionForLog(columnPath, column, colPosition) =>
          Map(
            "column" -> structFieldToMap(columnPath, column)
          ) ++ colPosition.map("position" -> _.toString)
      }))
  }
  /** Recorded when columns are changed. */
  case class ChangeColumn(
      columnPath: Seq[String],
      columnName: String,
      newColumn: StructField,
      colPosition: Option[String]) extends Operation("CHANGE COLUMN") {

    override val parameters: Map[String, Any] = Map(
      "column" -> JsonUtils.toJson(structFieldToMap(columnPath, newColumn))
    ) ++ colPosition.map("position" -> _)
  }
  /** Recorded when columns are replaced. */
  case class ReplaceColumns(
      columns: Seq[StructField]) extends Operation("REPLACE COLUMNS") {

    override val parameters: Map[String, Any] = Map(
      "columns" -> JsonUtils.toJson(columns.map(structFieldToMap(Seq.empty, _))))
  }

  case class UpgradeProtocol(newProtocol: Protocol) extends Operation("UPGRADE PROTOCOL") {
    override val parameters: Map[String, Any] = Map("newProtocol" -> JsonUtils.toJson(Map(
      "minReaderVersion" -> newProtocol.minReaderVersion,
      "minWriterVersion" -> newProtocol.minWriterVersion
    )))
  }

  object ManualUpdate extends Operation("Manual Update") {
    override val parameters: Map[String, Any] = Map.empty
  }

  object FileNotificationRetention extends Operation("FILE NOTIFICATION RETENTION") {
    override val parameters: Map[String, Any] = Map.empty
  }

  case class UpdateColumnMetadata(
      operationName: String,
      columns: Seq[(Seq[String], StructField)])
    extends Operation(operationName) {
    override val parameters: Map[String, Any] = {
      Map("columns" -> JsonUtils.toJson(columns.map {
        case (path, field) => structFieldToMap(path, field)
      }))
    }
  }

  /** Recorded when recomputing stats on the table. */
  case class ComputeStats(predicate: Seq[String]) extends Operation("COMPUTE STATS") {
    override val parameters: Map[String, Any] = Map(
      "predicate" -> JsonUtils.toJson(predicate))
  }

  /** Recorded when manually re-/un-/setting ZCube Information for existing files. */
  case class ResetZCubeInfo(predicate: Seq[String], zOrderBy: Seq[String])
    extends Operation("RESET ZCUBE INFO") {

    override val parameters: Map[String, Any] = Map(
      "predicate" -> JsonUtils.toJson(predicate),
      "zOrderBy" -> JsonUtils.toJson(zOrderBy))
  }

  case class UpdateSchema(oldSchema: StructType, newSchema: StructType)
      extends Operation("UPDATE SCHEMA") {
    override val parameters: Map[String, Any] = Map(
      "oldSchema" -> JsonUtils.toJson(oldSchema),
      "newSchema" -> JsonUtils.toJson(newSchema))
  }


  private def structFieldToMap(colPath: Seq[String], field: StructField): Map[String, Any] = {
    Map(
      "name" -> UnresolvedAttribute(colPath :+ field.name).name,
      "type" -> field.dataType.typeName,
      "nullable" -> field.nullable,
      "metadata" -> JsonUtils.mapper.readValue[Map[String, Any]](field.metadata.json)
    )
  }

  /**
   * Qualified column type with position. We define a copy of the type here to avoid depending on
   * the parser output classes in our logging.
   */
  case class QualifiedColTypeWithPositionForLog(
     columnPath: Seq[String],
     column: StructField,
     colPosition: Option[String])
}

private[delta] object DeltaOperationMetrics {
  val WRITE = Set(
    "numFiles", // number of files written
    "numOutputBytes", // size in bytes of the written contents
    "numOutputRows" // number of rows written
  )

  val STREAMING_UPDATE = Set(
    "numAddedFiles", // number of files added
    "numRemovedFiles", // number of files removed
    "numOutputRows", // number of rows written
    "numOutputBytes" // number of output writes
  )

  val DELETE = Set(
    "numAddedFiles", // number of files added
    "numRemovedFiles", // number of files removed
    "numDeletedRows", // number of rows removed
    "numCopiedRows" // number of rows copied in the process of deleting files
  )

  /** Deleting the entire table or partition would prevent row level metrics from being recorded */
  val DELETE_PARTITIONS = Set(
    "numRemovedFiles" // number of files removed
  )

  val TRUNCATE = Set(
    "numRemovedFiles" // number of files removed
  )

  val FSCK = Set(
    "numRemovedFiles" // number of files removed
  )

  val CONVERT = Set(
    "numConvertedFiles" // number of parquet files that have been converted.
  )

  val OPTIMIZE = Set(
    "numAddedFiles", // number of files added
    "numRemovedFiles", // number of files removed
    "numAddedBytes", // number of bytes added by optimize
    "numRemovedBytes", // number of bytes removed by optimize
    "minFileSize", // the size of the smallest file
    "p25FileSize", // the size of the 25th percentile file
    "p50FileSize", // the median file size
    "p75FileSize", // the 75th percentile of the file sizes
    "maxFileSize" // the size of the largest file
  )

  val MERGE = Set(
    "numSourceRows", // number of rows in the source dataframe
    "numTargetRowsInserted", // number of rows inserted into the target table.
    "numTargetRowsUpdated", // number of rows updated in the target table.
    "numTargetRowsDeleted", // number of rows deleted in the target table.
    "numTargetRowsCopied", // number of target rows copied
    "numOutputRows", // total number of rows written out
    "numTargetFilesAdded", // num files added to the sink(target)
    "numTargetFilesRemoved" // number of files removed from the sink(target)
  )

  val UPDATE = Set(
    "numAddedFiles", // number of files added
    "numRemovedFiles", // number of files removed
    "numUpdatedRows", // number of rows updated
    "numCopiedRows" // number of rows just copied over in the process of updating files.
  )

  val CLONE = Set(
    "sourceTableSize", // size in bytes of source table at version
    "sourceNumOfFiles", // number of files in source table at version
    "numRemovedFiles", // number of files removed from target table if delta table was replaced
    "numCopiedFiles", // number of files that were cloned - 0 for shallow tables
    "removedFilesSize", // size in bytes of files removed from an existing Delta table if one exists
    "copiedFilesSize" // size of files copied - 0 for shallow tables
  )
}
