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

package org.apache.spark.sql.delta.sources

// scalastyle:off import.ordering.noEmptyLine
import java.io.FileNotFoundException
import java.sql.Timestamp

import scala.util.matching.Regex

import org.apache.spark.sql.delta.{DeltaErrors, DeltaLog, DeltaOptions, DeltaTableUtils, DeltaTimeTravelSpec, StartingVersion, StartingVersionLatest}
import org.apache.spark.sql.delta.actions._
import org.apache.spark.sql.delta.files.DeltaSourceSnapshot
import org.apache.spark.sql.delta.metering.DeltaLogging
import org.apache.spark.sql.delta.schema.SchemaUtils
import org.apache.spark.sql.delta.util.{DateTimeUtils, TimestampFormatter}

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.catalyst.expressions.{Expression, Literal}
import org.apache.spark.sql.connector.read.streaming
import org.apache.spark.sql.connector.read.streaming.{ReadAllAvailable, ReadLimit, ReadMaxFiles, SupportsAdmissionControl}
import org.apache.spark.sql.execution.streaming._
import org.apache.spark.sql.types.StructType

/**
 * A case class to help with `Dataset` operations regarding Offset indexing, representing AddFile
 * actions in a Delta log. For proper offset tracking (SC-19523), there are also special sentinel
 * values with index = -1 and add = null.
 *
 * This class is not designed to be persisted in offset logs or such.
 *
 * @param version The version of the Delta log containing this AddFile.
 * @param index The index of this AddFile in the Delta log.
 * @param add The AddFile.
 * @param isLast A flag to indicate whether this is the last AddFile in the version. This is used
 *               to resolve an off-by-one issue in the streaming offset interface; once we've read
 *               to the end of a log version file, we check this flag to advance immediately to the
 *               next one in the persisted offset. Without this special case we would re-read the
 *               already completed log file.
 */
private[delta] case class IndexedFile(
    version: Long, index: Long, add: AddFile, isLast: Boolean = false)

/**
 * A streaming source for a Delta table.
 *
 * When a new stream is started, delta starts by constructing a
 * [[org.apache.spark.sql.delta.Snapshot]] at
 * the current version of the table. This snapshot is broken up into batches until
 * all existing data has been processed. Subsequent processing is done by tailing
 * the change log looking for new data. This results in the streaming query returning
 * the same answer as a batch query that had processed the entire dataset at any given point.
 */
case class DeltaSource(
    spark: SparkSession,
    deltaLog: DeltaLog,
    options: DeltaOptions,
    filters: Seq[Expression] = Nil)
  extends Source
  with SupportsAdmissionControl
  with DeltaLogging {

  // Deprecated. Please use `ignoreDeletes` or `ignoreChanges` from now on.
  private val ignoreFileDeletion = {
    if (options.ignoreFileDeletion) {
      logConsole(DeltaErrors.ignoreStreamingUpdatesAndDeletesWarning(spark))
      recordDeltaEvent(deltaLog, "delta.deprecation.ignoreFileDeletion")
    }
    options.ignoreFileDeletion
  }

  /** A check on the source table that disallows deletes on the source data. */
  private val ignoreChanges = options.ignoreChanges || ignoreFileDeletion

  /** A check on the source table that disallows commits that only include deletes to the data. */
  private val ignoreDeletes = options.ignoreDeletes || ignoreFileDeletion || ignoreChanges

  private val excludeRegex: Option[Regex] = options.excludeRegex

  override val schema: StructType = deltaLog.snapshot.metadata.schema

  // This was checked before creating ReservoirSource
  assert(schema.nonEmpty)

  private val tableId = deltaLog.snapshot.metadata.id

  private var previousOffset: DeltaSourceOffset = null

  // A metadata snapshot when starting the query.
  private var initialState: DeltaSourceSnapshot = null
  private var initialStateVersion: Long = -1L

  /**
   * Get the changes starting from (startVersion, startIndex). The start point should not be
   * included in the result.
   */
  private def getChanges(
      fromVersion: Long,
      fromIndex: Long,
      isStartingVersion: Boolean): Iterator[IndexedFile] = {

    /** Returns matching files that were added on or after startVersion among delta logs. */
    def filterAndIndexDeltaLogs(startVersion: Long): Iterator[IndexedFile] = {
      deltaLog.getChanges(startVersion).flatMap { case (version, actions) =>
        val addFiles = verifyStreamHygieneAndFilterAddFiles(actions, version)
        Iterator.single(IndexedFile(version, -1, null)) ++ addFiles
          .map(_.asInstanceOf[AddFile])
          .zipWithIndex.map { case (action, index) =>
          IndexedFile(version, index.toLong, action, isLast = index + 1 == addFiles.size)
        }
      }
    }

    val iter = if (isStartingVersion) {
      getSnapshotAt(fromVersion) ++ filterAndIndexDeltaLogs(fromVersion + 1)
    } else {
      filterAndIndexDeltaLogs(fromVersion)
    }

    iter.filter { case IndexedFile(version, index, _, _) =>
      version > fromVersion || (index == -1 || index > fromIndex)
    }
  }

  private def getSnapshotAt(version: Long): Iterator[IndexedFile] = {
    if (initialState == null || version != initialStateVersion) {
      cleanUpSnapshotResources()
      val snapshot =
        try {
          deltaLog.getSnapshotAt(version)
        } catch {
          case e: FileNotFoundException =>
            throw DeltaErrors.logFileNotFoundExceptionForStreamingSource(e)
        }
      initialState = new DeltaSourceSnapshot(spark, snapshot, filters)
      initialStateVersion = version
    }
    initialState.iterator()
  }

  private def iteratorLast[T](iter: Iterator[T]): Option[T] = {
    var last: Option[T] = None
    while (iter.hasNext) {
      last = Some(iter.next())
    }
    last
  }

  private def getChangesWithRateLimit(
      fromVersion: Long,
      fromIndex: Long,
      isStartingVersion: Boolean,
      limits: Option[AdmissionLimits] = Some(new AdmissionLimits())): Iterator[IndexedFile] = {
    val changes = getChanges(fromVersion, fromIndex, isStartingVersion)
    if (limits.isEmpty) return changes

    // Take each change until we've seen the configured number of addFiles. Some changes don't
    // represent file additions; we retain them for offset tracking, but they don't count towards
    // the maxFilesPerTrigger conf.
    var admissionControl = limits.get
    changes.takeWhile { action =>
      admissionControl.admit(Option(action.add))
    }
  }

  private def getStartingOffset(
      limits: Option[AdmissionLimits] = Some(new AdmissionLimits())): Option[Offset] = {

    val (version, isStartingVersion) = getStartingVersion match {
      case Some(v) => (v, false)
      case None => (deltaLog.snapshot.version, true)
    }
    if (version < 0) {
      return None
    }
    val last = iteratorLast(
      getChangesWithRateLimit(version, -1L, isStartingVersion = isStartingVersion, limits))
    if (last.isEmpty) {
      return None
    }
    val IndexedFile(v, i, _, lastOfVersion) = last.get
    assert(v >= version,
      s"getChangesWithRateLimit returns an invalid version: $v (expected: >= $version)")

    // If the last file of previous batch is the last file of its version, automatically bump
    // to next version to skip accessing that version file altogether.
    if (lastOfVersion) {
      // isStartingVersion must be false here as we have bumped the version
      Some(DeltaSourceOffset(tableId, v + 1, -1, isStartingVersion = false))
    } else {
      Some(DeltaSourceOffset(tableId, v, i, isStartingVersion = v == version))
    }
  }

  override def getDefaultReadLimit: ReadLimit = {
    new AdmissionLimits().toReadLimit
  }

  override def latestOffset(startOffset: streaming.Offset, limit: ReadLimit): streaming.Offset = {
    val currentOffset = if (previousOffset == null) {
      getStartingOffset(AdmissionLimits(limit))
    } else {
      val changes = getChangesWithRateLimit(
        previousOffset.reservoirVersion,
        previousOffset.index,
        previousOffset.isStartingVersion,
        AdmissionLimits(limit))
      val last = iteratorLast(changes)
      if (last.isEmpty) {
        Some(previousOffset)
      } else {
        val IndexedFile(v, i, _, lastOfVersion) = last.get
        val isStartingVersion =
          v == previousOffset.reservoirVersion && previousOffset.isStartingVersion

        // If the last file in previous batch is the last file of that version, automatically bump
        // to next version to skip accessing that version file altogether.
        if (lastOfVersion) {
          // isStartingVersion must be false here as we have bumped the version
          Some(DeltaSourceOffset(tableId, v + 1, -1, isStartingVersion = false))
        } else {
          Some(DeltaSourceOffset(tableId, v, i, isStartingVersion))
        }
      }
    }
    logDebug(s"previousOffset -> currentOffset: $previousOffset -> $currentOffset")
    currentOffset.orNull
  }

  override def getOffset: Option[Offset] = {
    throw new UnsupportedOperationException(
      "latestOffset(Offset, ReadLimit) should be called instead of this method")
  }

  private def verifyStreamHygieneAndFilterAddFiles(
    actions: Seq[Action],
    version: Long): Seq[Action] = {
    var seenFileAdd = false
    var removeFileActionPath: Option[String] = None
    val filteredActions = actions.filter {
      case a: AddFile if a.dataChange =>
        seenFileAdd = true
        true
      case a: AddFile =>
        // Created by a data compaction
        false
      case r: RemoveFile if r.dataChange =>
        if (removeFileActionPath.isEmpty) {
          removeFileActionPath = Some(r.path)
        }
        false
      case _: RemoveFile =>
        false
      case m: Metadata =>
        if (!SchemaUtils.isReadCompatible(m.schema, schema)) {
          throw DeltaErrors.schemaChangedException(schema, m.schema)
        }
        false
      case protocol: Protocol =>
        deltaLog.protocolRead(protocol)
        false
      case _: SetTransaction | _: CommitInfo =>
        false
      case null => // Some crazy future feature. Ignore
        false
    }
    if (removeFileActionPath.isDefined) {
      if (seenFileAdd && !ignoreChanges) {
        throw DeltaErrors.deltaSourceIgnoreChangesError(version, removeFileActionPath.get)
      } else if (!seenFileAdd && !ignoreDeletes) {
        throw DeltaErrors.deltaSourceIgnoreDeleteError(version, removeFileActionPath.get)
      }
    }

    filteredActions
  }

  override def getBatch(start: Option[Offset], end: Offset): DataFrame = {
    val endOffset = DeltaSourceOffset(tableId, end)
    previousOffset = endOffset // For recovery
    val changes = if (start.isEmpty) {
      getStartingVersion match {
        case Some(v) => getChanges(v, -1L, isStartingVersion = false)
        case _ =>
          if (endOffset.isStartingVersion) {
            getChanges(endOffset.reservoirVersion, -1L, isStartingVersion = true)
          } else {
            assert(
              endOffset.reservoirVersion > 0, s"invalid reservoirVersion in endOffset: $endOffset")
            // Load from snapshot `endOffset.reservoirVersion - 1L` so that `index` in `endOffset`
            // is still valid.
            getChanges(endOffset.reservoirVersion - 1L, -1L, isStartingVersion = true)
          }
      }
    } else {
      val startOffset = DeltaSourceOffset(tableId, start.get)
      if (!startOffset.isStartingVersion) {
        // unpersist `snapshot` because it won't be used any more.
        cleanUpSnapshotResources()
      }
      getChanges(startOffset.reservoirVersion, startOffset.index, startOffset.isStartingVersion)
    }

    val addFilesIter = changes.takeWhile { case IndexedFile(version, index, _, _) =>
      version < endOffset.reservoirVersion ||
        (version == endOffset.reservoirVersion && index <= endOffset.index)
    }.collect { case i: IndexedFile if i.add != null => i.add }
    val addFiles =
      addFilesIter.filter(a => excludeRegex.forall(_.findFirstIn(a.path).isEmpty)).toSeq
    logDebug(s"start: $start end: $end ${addFiles.toList}")
    deltaLog.createDataFrame(deltaLog.snapshot, addFiles, isStreaming = true)
  }

  override def stop(): Unit = {
    cleanUpSnapshotResources()
  }

  private def cleanUpSnapshotResources(): Unit = {
    if (initialState != null) {
      initialState.close(unpersistSnapshot = initialStateVersion < deltaLog.snapshot.version)
      initialState = null
    }
  }

  override def toString(): String = s"DeltaSource[${deltaLog.dataPath}]"

  /**
   * Class that helps controlling how much data should be processed by a single micro-batch.
   */
  private class AdmissionLimits(
      maxFiles: Option[Int] = options.maxFilesPerTrigger,
      private var bytesToTake: Long = options.maxBytesPerTrigger.getOrElse(Long.MaxValue)) {

    private var filesToTake = maxFiles.getOrElse {
      if (options.maxBytesPerTrigger.isEmpty) {
        DeltaOptions.MAX_FILES_PER_TRIGGER_OPTION_DEFAULT
      } else {
        Int.MaxValue - 8 // - 8 to prevent JVM Array allocation OOM
      }
    }

    /** Whether to admit the next file */
    def admit(add: Option[AddFile]): Boolean = {
      if (add.isEmpty) return true
      val shouldAdmit = filesToTake > 0 && bytesToTake > 0
      filesToTake -= 1
      bytesToTake -= add.get.size
      shouldAdmit
    }

    def toReadLimit: ReadLimit = {
      if (options.maxFilesPerTrigger.isDefined && options.maxBytesPerTrigger.isDefined) {
        CompositeLimit(
          ReadMaxBytes(options.maxBytesPerTrigger.get),
          ReadLimit.maxFiles(options.maxFilesPerTrigger.get).asInstanceOf[ReadMaxFiles])
      } else if (options.maxBytesPerTrigger.isDefined) {
        ReadMaxBytes(options.maxBytesPerTrigger.get)
      } else {
        ReadLimit.maxFiles(
          options.maxFilesPerTrigger.getOrElse(DeltaOptions.MAX_FILES_PER_TRIGGER_OPTION_DEFAULT))
      }
    }
  }

  private object AdmissionLimits {
    def apply(limit: ReadLimit): Option[AdmissionLimits] = limit match {
      case _: ReadAllAvailable => None
      case maxFiles: ReadMaxFiles => Some(new AdmissionLimits(Some(maxFiles.maxFiles())))
      case maxBytes: ReadMaxBytes => Some(new AdmissionLimits(None, maxBytes.maxBytes))
      case composite: CompositeLimit =>
        Some(new AdmissionLimits(Some(composite.files.maxFiles()), composite.bytes.maxBytes))
      case other => throw new UnsupportedOperationException(s"Unknown ReadLimit: $other")
    }
  }

  /**
   * Extracts whether users provided the option to time travel a relation. If a query restarts from
   * a checkpoint and the checkpoint has recorded the offset, this method should never been called.
   */
  private lazy val getStartingVersion: Option[Long] = {
    /** DeltaOption validates input and ensures that only one is provided. */
    if (options.startingVersion.isDefined) {
      val v = options.startingVersion.get match {
        case StartingVersionLatest =>
          deltaLog.update().version + 1
        case StartingVersion(version) =>
          // when starting from a given version, we don't need the snapshot of this version. So
          // `mustBeRecreatable` is set to `false`.
          deltaLog.history.checkVersionExists(version, mustBeRecreatable = false)
          version
      }
      Some(v)
    } else if (options.startingTimestamp.isDefined) {
      val tt: DeltaTimeTravelSpec = DeltaTimeTravelSpec(
        timestamp = options.startingTimestamp.map(Literal(_)),
        version = None,
        creationSource = Some("deltaSource"))
      val tz = spark.sessionState.conf.sessionLocalTimeZone
      val timestamp = tt.getTimestamp(tz)
      val commit = deltaLog.history.getActiveCommitAtTime(
        timestamp,
        canReturnLastCommit = true,
        mustBeRecreatable = false,
        canReturnEarliestCommit = true)
      if (commit.timestamp >= timestamp.getTime) {
        // Find the commit at the `timestamp` or the earliest commit
        Some(commit.version)
      } else {
        // commit.timestamp is not the same, so this commit is a commit before the timestamp and
        // the next version if exists should be the earliest commit after the timestamp.
        // Note: `getActiveCommitAtTime` has called `update`, so we don't need to call it again.
        if (commit.version + 1 <= deltaLog.snapshot.version) {
          Some(commit.version + 1)
        } else {
          val commitTs = new Timestamp(commit.timestamp)
          val timestampFormatter = TimestampFormatter(DateTimeUtils.getTimeZone(tz))
          val tsString = DateTimeUtils.timestampToString(
            timestampFormatter, DateTimeUtils.fromJavaTimestamp(commitTs))
          throw DeltaErrors.timestampGreaterThanLatestCommit(timestamp, commitTs, tsString)
        }
      }
    } else {
      None
    }
  }
}
