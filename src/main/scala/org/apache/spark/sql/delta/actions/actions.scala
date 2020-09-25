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

package org.apache.spark.sql.delta.actions

import java.net.URI
import java.sql.Timestamp
import java.util.Locale

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.sql.delta.{DeltaConfigs, DeltaErrors}
import org.apache.spark.sql.delta.schema.{Constraints, Invariants}
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.delta.util.JsonUtils
import com.fasterxml.jackson.annotation.{JsonIgnore, JsonInclude}
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.{JsonSerializer, SerializerProvider}
import com.fasterxml.jackson.databind.annotation.{JsonDeserialize, JsonSerialize}
import org.codehaus.jackson.annotate.JsonRawValue

import org.apache.spark.sql.{AnalysisException, SparkSession}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.util.Utils

// scalastyle:off
import org.apache.spark.internal.Logging
import org.apache.spark.sql.Encoder
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.types.{DataType, StructType}
// scalastyle:on

/** Thrown when the protocol version of a table is greater than supported by this client. */
class InvalidProtocolVersionException extends RuntimeException(
    "Delta protocol version is too new for this version of the Databricks Runtime. " +
    "Please upgrade to a newer release.")

class ProtocolDowngradeException(oldProtocol: Protocol, newProtocol: Protocol)
  extends RuntimeException("Protocol version cannot be downgraded from " +
    s"${oldProtocol.simpleString} to ${newProtocol.simpleString}")

object Action {
  /** The maximum version of the protocol that this version of Delta understands. */
  val readerVersion = 1
  val writerVersion = 3
  val protocolVersion: Protocol = Protocol(readerVersion, writerVersion)

  def fromJson(json: String): Action = {
    JsonUtils.mapper.readValue[SingleAction](json).unwrap
  }

  lazy val logSchema = ExpressionEncoder[SingleAction].schema
}

/**
 * Represents a single change to the state of a Delta table. An order sequence
 * of actions can be replayed using [[InMemoryLogReplay]] to derive the state
 * of the table at a given point in time.
 */
sealed trait Action {
  def wrap: SingleAction
  def json: String = JsonUtils.toJson(wrap)
}

/**
 * Used to block older clients from reading or writing the log when backwards
 * incompatible changes are made to the protocol. Readers and writers are
 * responsible for checking that they meet the minimum versions before performing
 * any other operations.
 *
 * Since this action allows us to explicitly block older clients in the case of a
 * breaking change to the protocol, clients should be tolerant of messages and
 * fields that they do not understand.
 */
case class Protocol(
    minReaderVersion: Int = Action.readerVersion,
    minWriterVersion: Int = Action.writerVersion) extends Action {
  override def wrap: SingleAction = SingleAction(protocol = this)
  @JsonIgnore
  def simpleString: String = s"($minReaderVersion,$minWriterVersion)"
}

object Protocol {
  def apply(conf: SQLConf, requiredProtocol: Option[Protocol]): Protocol = {
    val reader = math.max(
      requiredProtocol.map(_.minReaderVersion).getOrElse(0),
      conf.getConf(DeltaSQLConf.DELTA_PROTOCOL_DEFAULT_READER_VERSION)
    )
    val writer = math.max(
      requiredProtocol.map(_.minWriterVersion).getOrElse(0),
      conf.getConf(DeltaSQLConf.DELTA_PROTOCOL_DEFAULT_WRITER_VERSION)
    )
    Protocol(minReaderVersion = reader, minWriterVersion = writer)
  }

  /** Picks the protocol version for a new table given potential feature usage. */
  def forNewTable(spark: SparkSession, metadata: Metadata): Protocol = {
    val requiredProtocol = requiredMinimumProtocol(spark, metadata)._1
    Protocol(spark.sessionState.conf, Some(requiredProtocol))
  }

  /**
   * Given the Delta table metadata, returns the minimum required table protocol version
   * and the features used that require the protocol.
   */
  private def requiredMinimumProtocol(
      spark: SparkSession,
      metadata: Metadata): (Protocol, Seq[String]) = {
    val featuresUsed = new ArrayBuffer[String]()
    var minimumRequired = Protocol(0, 0)
    // Check for invariants in the schema
    if (Invariants.getFromSchema(metadata.schema, spark).nonEmpty) {
      minimumRequired = Protocol(0, minWriterVersion = 2)
      featuresUsed.append("Setting column level invariants")
    }

    val configs = metadata.configuration.map { case (k, v) => k.toLowerCase(Locale.ROOT) -> v }
    if (configs.contains(DeltaConfigs.IS_APPEND_ONLY.key.toLowerCase(Locale.ROOT))) {
      minimumRequired = Protocol(0, minWriterVersion = 2)
      featuresUsed.append(s"Append only tables (${DeltaConfigs.IS_APPEND_ONLY.key})")
    }

    if (Constraints.getCheckConstraints(metadata, spark).nonEmpty) {
      minimumRequired = Protocol(0, minWriterVersion = 3)
      featuresUsed.append("Setting CHECK constraints")
    }

    minimumRequired -> featuresUsed
  }

  /**
   * Verify that the protocol version of the table satisfies the version requirements of all the
   * configurations to be set for the table.
   */
  def assertProtocolRequirements(
      spark: SparkSession,
      metadata: Metadata,
      current: Protocol): Unit = {
    val (required, features) = requiredMinimumProtocol(spark, metadata)
    if (current.minWriterVersion < required.minWriterVersion ||
        current.minReaderVersion < required.minReaderVersion) {
      throw DeltaErrors.requireProtocolUpgrade(features, required, current)
    }
  }
}

/**
 * Sets the committed version for a given application. Used to make operations
 * like streaming append idempotent.
 */
case class SetTransaction(
    appId: String,
    version: Long,
    @JsonDeserialize(contentAs = classOf[java.lang.Long])
    lastUpdated: Option[Long]) extends Action {
  override def wrap: SingleAction = SingleAction(txn = this)
}

/** Actions pertaining to the addition and removal of files. */
sealed trait FileAction extends Action {
  val path: String
  val dataChange: Boolean
  @JsonIgnore
  lazy val pathAsUri: URI = new URI(path)
}

/**
 * Adds a new file to the table. When multiple [[AddFile]] file actions
 * are seen with the same `path` only the metadata from the last one is
 * kept.
 */
case class AddFile(
    path: String,
    @JsonInclude(JsonInclude.Include.ALWAYS)
    partitionValues: Map[String, String],
    size: Long,
    modificationTime: Long,
    dataChange: Boolean,
    @JsonRawValue
    stats: String = null,
    tags: Map[String, String] = null) extends FileAction {
  require(path.nonEmpty)

  override def wrap: SingleAction = SingleAction(add = this)

  def remove: RemoveFile = removeWithTimestamp()

  def removeWithTimestamp(
      timestamp: Long = System.currentTimeMillis(),
      dataChange: Boolean = true): RemoveFile = {
    // scalastyle:off
    RemoveFile(path, Some(timestamp), dataChange)
    // scalastyle:on
  }
}

object AddFile {
  /**
   * Misc file-level metadata.
   *
   * The convention is that clients may safely ignore any/all of these tags and this should never
   * have an impact on correctness.
   *
   * Otherwise, the information should go as a field of the AddFile action itself and the Delta
   * protocol version should be bumped.
   */
  object Tags {
    sealed abstract class KeyType(val name: String)

    /** [[ZCUBE_ID]]: identifier of the OPTIMIZE ZORDER BY job that this file was produced by */
    object ZCUBE_ID extends AddFile.Tags.KeyType("ZCUBE_ID")

    /** [[ZCUBE_ZORDER_BY]]: ZOrdering of the corresponding ZCube */
    object ZCUBE_ZORDER_BY extends AddFile.Tags.KeyType("ZCUBE_ZORDER_BY")

    /** [[ZCUBE_ZORDER_CURVE]]: Clustering strategy of the corresponding ZCube */
    object ZCUBE_ZORDER_CURVE extends AddFile.Tags.KeyType("ZCUBE_ZORDER_CURVE")
  }

  /** Convert a [[Tags.KeyType]] to a string to be used in the AddMap.tags Map[String, String]. */
  def tag(tagKey: Tags.KeyType): String = tagKey.name
}

/**
 * Logical removal of a given file from the reservoir. Acts as a tombstone before a file is
 * deleted permanently.
 */
// scalastyle:off
case class RemoveFile(
    path: String,
    @JsonDeserialize(contentAs = classOf[java.lang.Long])
    deletionTimestamp: Option[Long],
    dataChange: Boolean = true) extends FileAction {
  override def wrap: SingleAction = SingleAction(remove = this)

  @JsonIgnore
  val delTimestamp: Long = deletionTimestamp.getOrElse(0L)
}
// scalastyle:on

case class Format(
    provider: String = "parquet",
    options: Map[String, String] = Map.empty)

/**
 * Updates the metadata of the table. Only the last update to the [[Metadata]]
 * of a table is kept. It is the responsibility of the writer to ensure that
 * any data already present in the table is still valid after any change.
 */
case class Metadata(
    id: String = if (Utils.isTesting) "testId" else java.util.UUID.randomUUID().toString,
    name: String = null,
    description: String = null,
    format: Format = Format(),
    schemaString: String = null,
    partitionColumns: Seq[String] = Nil,
    configuration: Map[String, String] = Map.empty,
    @JsonDeserialize(contentAs = classOf[java.lang.Long])
    createdTime: Option[Long] = Some(System.currentTimeMillis())) extends Action {

  // The `schema` and `partitionSchema` methods should be vals or lazy vals, NOT
  // defs, because parsing StructTypes from JSON is extremely expensive and has
  // caused perf. problems here in the past:

  /** Returns the schema as a [[StructType]] */
  @JsonIgnore
  lazy val schema: StructType =
    Option(schemaString).map { s =>
      DataType.fromJson(s).asInstanceOf[StructType]
    }.getOrElse(StructType.apply(Nil))

  /** Returns the partitionSchema as a [[StructType]] */
  @JsonIgnore
  lazy val partitionSchema: StructType =
    new StructType(partitionColumns.map(c => schema(c)).toArray)

  /** Columns written out to files. */
  @JsonIgnore
  lazy val dataSchema: StructType = {
    val partitions = partitionColumns.toSet
    StructType(schema.filterNot(f => partitions.contains(f.name)))
  }

  override def wrap: SingleAction = SingleAction(metaData = this)
}

/**
 * Interface for objects that represents the information for a commit. Commits can be referred to
 * using a version and timestamp. The timestamp of a commit comes from the remote storage
 * `lastModifiedTime`, and can be adjusted for clock skew. Hence we have the method `withTimestamp`.
 */
trait CommitMarker {
  /** Get the timestamp of the commit as millis after the epoch. */
  def getTimestamp: Long
  /** Return a copy object of this object with the given timestamp. */
  def withTimestamp(timestamp: Long): CommitMarker
  /** Get the version of the commit. */
  def getVersion: Long
}

/**
 * Holds provenance information about changes to the table. This [[Action]]
 * is not stored in the checkpoint and has reduced compatibility guarantees.
 * Information stored in it is best effort (i.e. can be falsified by the writer).
 */
case class CommitInfo(
    // The commit version should be left unfilled during commit(). When reading a delta file, we can
    // infer the commit version from the file name and fill in this field then.
    @JsonDeserialize(contentAs = classOf[java.lang.Long])
    version: Option[Long],
    timestamp: Timestamp,
    userId: Option[String],
    userName: Option[String],
    operation: String,
    @JsonSerialize(using = classOf[JsonMapSerializer])
    operationParameters: Map[String, String],
    job: Option[JobInfo],
    notebook: Option[NotebookInfo],
    clusterId: Option[String],
    @JsonDeserialize(contentAs = classOf[java.lang.Long])
    readVersion: Option[Long],
    isolationLevel: Option[String],
    /** Whether this commit has blindly appended without caring about existing files */
    isBlindAppend: Option[Boolean],
    operationMetrics: Option[Map[String, String]],
    userMetadata: Option[String]) extends Action with CommitMarker {
  override def wrap: SingleAction = SingleAction(commitInfo = this)

  override def withTimestamp(timestamp: Long): CommitInfo = {
    this.copy(timestamp = new Timestamp(timestamp))
  }

  override def getTimestamp: Long = timestamp.getTime
  @JsonIgnore
  override def getVersion: Long = version.get
}

case class JobInfo(
    jobId: String,
    jobName: String,
    runId: String,
    jobOwnerId: String,
    triggerType: String)

object JobInfo {
  def fromContext(context: Map[String, String]): Option[JobInfo] = {
    context.get("jobId").map { jobId =>
      JobInfo(
        jobId,
        context.get("jobName").orNull,
        context.get("runId").orNull,
        context.get("jobOwnerId").orNull,
        context.get("jobTriggerType").orNull)
    }
  }
}

case class NotebookInfo(notebookId: String)

object NotebookInfo {
  def fromContext(context: Map[String, String]): Option[NotebookInfo] = {
    context.get("notebookId").map { nbId => NotebookInfo(nbId) }
  }
}

object CommitInfo {
  def empty(version: Option[Long] = None): CommitInfo = {
    CommitInfo(version, null, None, None, null, null, None, None,
                None, None, None, None, None, None)
  }

  def apply(
      time: Long,
      operation: String,
      operationParameters: Map[String, String],
      commandContext: Map[String, String],
      readVersion: Option[Long],
      isolationLevel: Option[String],
      isBlindAppend: Option[Boolean],
      operationMetrics: Option[Map[String, String]],
      userMetadata: Option[String]): CommitInfo = {
    val getUserName = commandContext.get("user").flatMap {
      case "unknown" => None
      case other => Option(other)
    }

    CommitInfo(
      None,
      new Timestamp(time),
      commandContext.get("userId"),
      getUserName,
      operation,
      operationParameters,
      JobInfo.fromContext(commandContext),
      NotebookInfo.fromContext(commandContext),
      commandContext.get("clusterId"),
      readVersion,
      isolationLevel,
      isBlindAppend,
      operationMetrics,
      userMetadata)
  }
}

/** A serialization helper to create a common action envelope. */
case class SingleAction(
    txn: SetTransaction = null,
    add: AddFile = null,
    remove: RemoveFile = null,
    metaData: Metadata = null,
    protocol: Protocol = null,
    commitInfo: CommitInfo = null) {

  def unwrap: Action = {
    if (add != null) {
      add
    } else if (remove != null) {
      remove
    } else if (metaData != null) {
      metaData
    } else if (txn != null) {
      txn
    } else if (protocol != null) {
      protocol
    } else if (commitInfo != null) {
      commitInfo
    } else {
      null
    }
  }
}

object SingleAction extends Logging {
  private lazy val _encoder: ExpressionEncoder[SingleAction] = try {
    ExpressionEncoder[SingleAction]()
  } catch {
    case e: Throwable =>
      logError(e.getMessage, e)
      throw e
  }

  private lazy val _addFileEncoder: ExpressionEncoder[AddFile] = try {
    ExpressionEncoder[AddFile]()
  } catch {
    case e: Throwable =>
      logError(e.getMessage, e)
      throw e
  }


  implicit def encoder: Encoder[SingleAction] = {
    _encoder.copy()
  }

  implicit def addFileEncoder: Encoder[AddFile] = {
    _addFileEncoder.copy()
  }
}

/** Serializes Maps containing JSON strings without extra escaping. */
class JsonMapSerializer extends JsonSerializer[Map[String, String]] {
  def serialize(
      parameters: Map[String, String],
      jgen: JsonGenerator,
      provider: SerializerProvider): Unit = {
    jgen.writeStartObject()
    parameters.foreach { case (key, value) =>
      if (value == null) {
        jgen.writeNullField(key)
      } else {
        jgen.writeFieldName(key)
        // Write value as raw data, since it's already JSON text
        jgen.writeRawValue(value)
      }
    }
    jgen.writeEndObject()
  }
}
