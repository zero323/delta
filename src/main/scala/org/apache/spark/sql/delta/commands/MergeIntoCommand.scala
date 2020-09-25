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

package org.apache.spark.sql.delta.commands

import scala.collection.JavaConverters._

import org.apache.spark.sql.delta._
import org.apache.spark.sql.delta.actions.AddFile
import org.apache.spark.sql.delta.files._
import org.apache.spark.sql.delta.schema.ImplicitMetadataOperation
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.delta.util.{AnalysisHelper, SetAccumulator}

import org.apache.spark.SparkContext
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.encoders.{ExpressionEncoder, RowEncoder}
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, AttributeReference, Expression, Literal, PredicateHelper, UnsafeProjection}
import org.apache.spark.sql.catalyst.expressions.BasePredicate
import org.apache.spark.sql.catalyst.expressions.NamedExpression
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.execution.SQLExecution
import org.apache.spark.sql.execution.command.RunnableCommand
import org.apache.spark.sql.execution.metric.{SQLMetric, SQLMetrics}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{StructField, StructType}

case class MergeDataRows(rows: Long)
case class MergeDataFiles(files: Long)

/**
 * Represents the state of a single merge clause:
 * - merge clause's (optional) predicate
 * - action type (insert, update, delete)
 * - action's expressions
 */
case class MergeClauseStats(
    condition: Option[String],
    actionType: String,
    actionExpr: Array[String])

object MergeClauseStats {
  def apply(mergeClause: DeltaMergeIntoClause): MergeClauseStats = {
    MergeClauseStats(
      condition = mergeClause.condition.map(_.sql),
      mergeClause.clauseType.toLowerCase(),
      actionExpr = mergeClause.actions.map(_.sql).toArray)
  }
}

/** State for a merge operation */
case class MergeStats(
    // Merge condition expression
    conditionExpr: String,

    // Expressions used in old MERGE stats, now always Null
    updateConditionExpr: String,
    updateExprs: Array[String],
    insertConditionExpr: String,
    insertExprs: Array[String],
    deleteConditionExpr: String,

    // Newer expressions used in MERGE with any number of MATCHED/NOT MATCHED
    matchedStats: Seq[MergeClauseStats],
    notMatchedStats: Seq[MergeClauseStats],

    // Data sizes of source and target at different stages of processing
    source: MergeDataRows,
    targetBeforeSkipping: MergeDataFiles,
    targetAfterSkipping: MergeDataFiles,

    // Data change sizes
    targetFilesRemoved: Long,
    targetFilesAdded: Long,
    targetRowsCopied: Long,
    targetRowsUpdated: Long,
    targetRowsInserted: Long,
    targetRowsDeleted: Long)

object MergeStats {
  // scalastyle:off argcount
  /** constructor to provide default values for deprecated fields */
  def apply(
      conditionExpr: String,
      matchedStats: Seq[MergeClauseStats],
      notMatchedStats: Seq[MergeClauseStats],
      source: MergeDataRows,
      targetBeforeSkipping: MergeDataFiles,
      targetAfterSkipping: MergeDataFiles,
      targetFilesRemoved: Long,
      targetFilesAdded: Long,
      targetRowsCopied: Long,
      targetRowsUpdated: Long,
      targetRowsInserted: Long,
      targetRowsDeleted: Long): MergeStats = {
    MergeStats(
      conditionExpr,
      updateConditionExpr = null,
      updateExprs = null,
      insertConditionExpr = null,
      insertExprs = null,
      deleteConditionExpr = null,
      matchedStats,
      notMatchedStats,
      source,
      targetBeforeSkipping,
      targetAfterSkipping,
      targetFilesRemoved,
      targetFilesAdded,
      targetRowsCopied,
      targetRowsUpdated,
      targetRowsInserted,
      targetRowsDeleted)
  }
  // scalastyle:on argcount
}

/**
 * Performs a merge of a source query/table into a Delta table.
 *
 * Issues an error message when the ON search_condition of the MERGE statement can match
 * a single row from the target table with multiple rows of the source table-reference.
 *
 * Algorithm:
 *
 * Phase 1: Find the input files in target that are touched by the rows that satisfy
 *    the condition and verify that no two source rows match with the same target row.
 *    This is implemented as an inner-join using the given condition. See [[findTouchedFiles]]
 *    for more details.
 *
 * Phase 2: Read the touched files again and write new files with updated and/or inserted rows.
 *
 * Phase 3: Use the Delta protocol to atomically remove the touched files and add the new files.
 *
 * @param source            Source data to merge from
 * @param target            Target table to merge into
 * @param targetFileIndex   TahoeFileIndex of the target table
 * @param condition         Condition for a source row to match with a target row
 * @param matchedClauses    All info related to matched clauses.
 * @param notMatchedClauses  All info related to not matched clause.
 * @param migratedSchema    The final schema of the target - may be changed by schema evolution.
 */
case class MergeIntoCommand(
    @transient source: LogicalPlan,
    @transient target: LogicalPlan,
    @transient targetFileIndex: TahoeFileIndex,
    condition: Expression,
    matchedClauses: Seq[DeltaMergeIntoMatchedClause],
    notMatchedClauses: Seq[DeltaMergeIntoInsertClause],
    migratedSchema: Option[StructType]) extends RunnableCommand
  with DeltaCommand with PredicateHelper with AnalysisHelper with ImplicitMetadataOperation {

  import SQLMetrics._
  import MergeIntoCommand._

  override val canMergeSchema: Boolean = conf.getConf(DeltaSQLConf.DELTA_SCHEMA_AUTO_MIGRATE)
  override val canOverwriteSchema: Boolean = false

  @transient private lazy val sc: SparkContext = SparkContext.getOrCreate()
  @transient private lazy val targetDeltaLog: DeltaLog = targetFileIndex.deltaLog

  /** Whether this merge statement has only a single insert (NOT MATCHED) clause. */
  private def isSingleInsertOnly: Boolean = matchedClauses.isEmpty && notMatchedClauses.length == 1
  /** Whether this merge statement has only MATCHED clauses. */
  private def isMatchedOnly: Boolean = notMatchedClauses.isEmpty && matchedClauses.nonEmpty

  override lazy val metrics = Map[String, SQLMetric](
    "numSourceRows" -> createMetric(sc, "number of source rows"),
    "numTargetRowsCopied" -> createMetric(sc, "number of target rows rewritten unmodified"),
    "numTargetRowsInserted" -> createMetric(sc, "number of inserted rows"),
    "numTargetRowsUpdated" -> createMetric(sc, "number of updated rows"),
    "numTargetRowsDeleted" -> createMetric(sc, "number of deleted rows"),
    "numTargetFilesBeforeSkipping" -> createMetric(sc, "number of target files before skipping"),
    "numTargetFilesAfterSkipping" -> createMetric(sc, "number of target files after skipping"),
    "numTargetFilesRemoved" -> createMetric(sc, "number of files removed to target"),
    "numTargetFilesAdded" -> createMetric(sc, "number of files added to target"))

  override def run(
    spark: SparkSession): Seq[Row] = recordDeltaOperation(targetDeltaLog, "delta.dml.merge") {
    targetDeltaLog.withNewTransaction { deltaTxn =>
      if (target.schema.size != deltaTxn.metadata.schema.size) {
        throw DeltaErrors.schemaChangedSinceAnalysis(
          atAnalysis = target.schema, latestSchema = deltaTxn.metadata.schema)
      }

      if (canMergeSchema) {
        updateMetadata(
          spark, deltaTxn, migratedSchema.getOrElse(target.schema),
          deltaTxn.metadata.partitionColumns, deltaTxn.metadata.configuration,
          isOverwriteMode = false, rearrangeOnly = false)
      }

      val deltaActions = {
       if (isSingleInsertOnly && spark.conf.get(DeltaSQLConf.MERGE_INSERT_ONLY_ENABLED)) {
         writeInsertsOnlyWhenNoMatchedClauses(spark, deltaTxn)
       } else {
         val filesToRewrite =
           recordDeltaOperation(targetDeltaLog, "delta.dml.merge.findTouchedFiles") {
             findTouchedFiles(spark, deltaTxn)
           }
         val newWrittenFiles = writeAllChanges(spark, deltaTxn, filesToRewrite)
         filesToRewrite.map(_.remove) ++ newWrittenFiles
       }
      }
      deltaTxn.registerSQLMetrics(spark, metrics)
      deltaTxn.commit(
        deltaActions,
        DeltaOperations.Merge(
          Option(condition.sql),
          matchedClauses.map(DeltaOperations.MergePredicate(_)),
          notMatchedClauses.map(DeltaOperations.MergePredicate(_))))

      // Record metrics
      val stats = MergeStats(
        // Merge Condition expression
        conditionExpr = condition.sql,

        // MATCHED/NOT MATCHED clauses used in MERGE
        matchedStats = matchedClauses.map(MergeClauseStats(_)),
        notMatchedStats = notMatchedClauses.map(MergeClauseStats(_)),

        // Data sizes of source and target at different stages of processing
        MergeDataRows(metrics("numSourceRows").value),
        targetBeforeSkipping =
          MergeDataFiles(metrics("numTargetFilesBeforeSkipping").value),
        targetAfterSkipping =
          MergeDataFiles(metrics("numTargetFilesAfterSkipping").value),

        // Data change sizes
        targetFilesAdded = metrics("numTargetFilesAdded").value,
        targetFilesRemoved = metrics("numTargetFilesRemoved").value,
        targetRowsCopied = metrics("numTargetRowsCopied").value,
        targetRowsUpdated = metrics("numTargetRowsUpdated").value,
        targetRowsInserted = metrics("numTargetRowsInserted").value,
        targetRowsDeleted = metrics("numTargetRowsDeleted").value)
      recordDeltaEvent(targetFileIndex.deltaLog, "delta.dml.merge.stats", data = stats)

      // This is needed to make the SQL metrics visible in the Spark UI
      val executionId = spark.sparkContext.getLocalProperty(SQLExecution.EXECUTION_ID_KEY)
      SQLMetrics.postDriverMetricUpdates(spark.sparkContext, executionId, metrics.values.toSeq)
    }
    spark.sharedState.cacheManager.recacheByPlan(spark, target)
    Seq.empty
  }

  /**
   * Find the target table files that contain the rows that satisfy the merge condition. This is
   * implemented as an inner-join between the source query/table and the target table using
   * the merge condition.
   */
  private def findTouchedFiles(
    spark: SparkSession,
    deltaTxn: OptimisticTransaction
  ): Seq[AddFile] = {

    // Accumulator to collect all the distinct touched files
    val touchedFilesAccum = new SetAccumulator[String]()
    spark.sparkContext.register(touchedFilesAccum, TOUCHED_FILES_ACCUM_NAME)

    // UDFs to records touched files names and add them to the accumulator
    val recordTouchedFileName = udf { (fileName: String) => {
      touchedFilesAccum.add(fileName)
      1
    }}.asNondeterministic()

    // Skip data based on the merge condition
    val targetOnlyPredicates =
      splitConjunctivePredicates(condition).filter(_.references.subsetOf(target.outputSet))
    val dataSkippedFiles = deltaTxn.filterFiles(targetOnlyPredicates)

    // Apply inner join to between source and target using the merge condition to find matches
    // In addition, we attach two columns
    // - a monotonically increasing row id for target rows to later identify whether the same
    //     target row is modified by multiple user or not
    // - the target file name the row is from to later identify the files touched by matched rows
    val joinToFindTouchedFiles = {
      val sourceDF = Dataset.ofRows(spark, source)
      val targetDF = Dataset.ofRows(spark, buildTargetPlanWithFiles(deltaTxn, dataSkippedFiles))
        .withColumn(ROW_ID_COL, monotonically_increasing_id())
        .withColumn(FILE_NAME_COL, input_file_name())
      sourceDF.join(targetDF, new Column(condition), "inner")
    }

    // Process the matches from the inner join to record touched files and find multiple matches
    val collectTouchedFiles = joinToFindTouchedFiles
      .select(col(ROW_ID_COL), recordTouchedFileName(col(FILE_NAME_COL)).as("one"))

    // Calculate frequency of matches per source row
    val matchedRowCounts = collectTouchedFiles.groupBy(ROW_ID_COL).agg(sum("one").as("count"))

    // Get multiple matches and simultaneously collect (using touchedFilesAccum) the file names
    val multipleMatchCount = matchedRowCounts.filter("count > 1").count()

    // Throw error if multiple matches are ambiguous or cannot be computed correctly.
    val canBeComputedUnambiguously = {
      // Multiple matches are not ambiguous when there is only one unconditional delete as
      // all the matched row pairs in the 2nd join in `writeAllChanges` will get deleted.
      val isUnconditionalDelete = matchedClauses.headOption match {
        case Some(DeltaMergeIntoDeleteClause(None)) => true
        case _ => false
      }
      matchedClauses.size == 1 && isUnconditionalDelete
    }

    if (multipleMatchCount > 0 && !canBeComputedUnambiguously) {
      throw DeltaErrors.multipleSourceRowMatchingTargetRowInMergeException(spark)
    }

    // Get the AddFiles using the touched file names.
    val touchedFileNames = touchedFilesAccum.value.iterator().asScala.toSeq
    logTrace(s"findTouchedFiles: matched files:\n\t${touchedFileNames.mkString("\n\t")}")

    val nameToAddFileMap = generateCandidateFileMap(targetDeltaLog.dataPath, dataSkippedFiles)
    val touchedAddFiles = touchedFileNames.map(f =>
      getTouchedFile(targetDeltaLog.dataPath, f, nameToAddFileMap))

    metrics("numTargetFilesBeforeSkipping") += deltaTxn.snapshot.numOfFiles
    metrics("numTargetFilesAfterSkipping") += dataSkippedFiles.size
    metrics("numTargetFilesRemoved") += touchedAddFiles.size
    touchedAddFiles
  }

  /**
   * This is an optimization of the case when there is no update clause for the merge.
   * We perform an left anti join on the source data to find the rows to be inserted.
   *
   * This will currently only optimize for the case when there is a _single_ notMatchedClause.
   */
  private def writeInsertsOnlyWhenNoMatchedClauses(
      spark: SparkSession,
      deltaTxn: OptimisticTransaction
    ): Seq[AddFile] = withStatusCode("DELTA", s"Writing new files " +
    s"for insert-only MERGE operation") {

    // UDFs to update metrics
    val incrSourceRowCountExpr = makeMetricUpdateUDF("numSourceRows")
    val incrInsertedCountExpr = makeMetricUpdateUDF("numTargetRowsInserted")

    val outputColNames = getTargetOutputCols(deltaTxn).map(_.name)
    // we use head here since we know there is only a single notMatchedClause
    val outputExprs = notMatchedClauses.head.resolvedActions.map(_.expr) :+ incrInsertedCountExpr
    val outputCols = outputExprs.zip(outputColNames).map { case (expr, name) =>
      new Column(Alias(expr, name)())
    }

    // source DataFrame
    val sourceDF = Dataset.ofRows(spark, source)
      .filter(new Column(incrSourceRowCountExpr))
      .filter(new Column(notMatchedClauses.head.condition.getOrElse(Literal(true))))

    // Skip data based on the merge condition
    val conjunctivePredicates = splitConjunctivePredicates(condition)
    val targetOnlyPredicates =
      conjunctivePredicates.filter(_.references.subsetOf(target.outputSet))
    val dataSkippedFiles = deltaTxn.filterFiles(targetOnlyPredicates)

    // target DataFrame
    val targetDF = Dataset.ofRows(
      spark, buildTargetPlanWithFiles(deltaTxn, dataSkippedFiles))

    val insertDf = sourceDF.join(targetDF, new Column(condition), "leftanti")
      .select(outputCols: _*)

    val newFiles = deltaTxn
      .writeFiles(repartitionIfNeeded(spark, insertDf, deltaTxn.metadata.partitionColumns))
    metrics("numTargetFilesBeforeSkipping") += deltaTxn.snapshot.numOfFiles
    metrics("numTargetFilesAfterSkipping") += dataSkippedFiles.size
    metrics("numTargetFilesRemoved") += 0
    metrics("numTargetFilesAdded") += newFiles.size
    newFiles
  }

  /**
   * Write new files by reading the touched files and updating/inserting data using the source
   * query/table. This is implemented using a full|right-outer-join using the merge condition.
   */
  private def writeAllChanges(
    spark: SparkSession,
    deltaTxn: OptimisticTransaction,
    filesToRewrite: Seq[AddFile]
  ): Seq[AddFile] = {
    val targetOutputCols = getTargetOutputCols(deltaTxn)

    // Generate a new logical plan that has same output attributes exprIds as the target plan.
    // This allows us to apply the existing resolved update/insert expressions.
    val newTarget = buildTargetPlanWithFiles(deltaTxn, filesToRewrite)
    val joinType = if (isMatchedOnly &&
      spark.conf.get(DeltaSQLConf.MERGE_MATCHED_ONLY_ENABLED)) {
      "rightOuter"
    } else {
      "fullOuter"
    }

    logDebug(s"""writeAllChanges using $joinType join:
                |  source.output: ${source.outputSet}
                |  target.output: ${target.outputSet}
                |  condition: $condition
                |  newTarget.output: ${newTarget.outputSet}
       """.stripMargin)

    // UDFs to update metrics
    val incrSourceRowCountExpr = makeMetricUpdateUDF("numSourceRows")
    val incrUpdatedCountExpr = makeMetricUpdateUDF("numTargetRowsUpdated")
    val incrInsertedCountExpr = makeMetricUpdateUDF("numTargetRowsInserted")
    val incrNoopCountExpr = makeMetricUpdateUDF("numTargetRowsCopied")
    val incrDeletedCountExpr = makeMetricUpdateUDF("numTargetRowsDeleted")

    // Apply an outer join to find both, matches and non-matches. We are adding two boolean fields
    // with value `true`, one to each side of the join. Whether this field is null or not after
    // the outer join, will allow us to identify whether the resultant joined row was a
    // matched inner result or an unmatched result with null on one side.
    val joinedDF = {
      val sourceDF = Dataset.ofRows(spark, source)
        .withColumn(SOURCE_ROW_PRESENT_COL, new Column(incrSourceRowCountExpr))
      val targetDF = Dataset.ofRows(spark, newTarget)
        .withColumn(TARGET_ROW_PRESENT_COL, lit(true))
      sourceDF.join(targetDF, new Column(condition), joinType)
    }

    val joinedPlan = joinedDF.queryExecution.analyzed

    def resolveOnJoinedPlan(exprs: Seq[Expression]): Seq[Expression] = {
      exprs.map { expr => tryResolveReferences(spark)(expr, joinedPlan) }
    }

    def matchedClauseOutput(clause: DeltaMergeIntoMatchedClause): Seq[Expression] = {
      val exprs = clause match {
        case u: DeltaMergeIntoUpdateClause =>
          // Generate update expressions and set ROW_DELETED_COL = false
          u.resolvedActions.map(_.expr) :+ Literal(false) :+ incrUpdatedCountExpr
        case _: DeltaMergeIntoDeleteClause =>
          // Generate expressions to set the ROW_DELETED_COL = true
          targetOutputCols :+ Literal(true) :+ incrDeletedCountExpr
      }
      resolveOnJoinedPlan(exprs)
    }

    def notMatchedClauseOutput(clause: DeltaMergeIntoInsertClause): Seq[Expression] = {
      val exprs = clause.resolvedActions.map(_.expr) :+ Literal(false) :+ incrInsertedCountExpr
      resolveOnJoinedPlan(exprs)
    }

    def clauseCondition(clause: DeltaMergeIntoClause): Expression = {
      // if condition is None, then expression always evaluates to true
      val condExpr = clause.condition.getOrElse(Literal(true))
      resolveOnJoinedPlan(Seq(condExpr)).head
    }

    val joinedRowEncoder = RowEncoder(joinedPlan.schema)
    val outputRowEncoder = RowEncoder(deltaTxn.metadata.schema).resolveAndBind()

    val processor = new JoinedRowProcessor(
      targetRowHasNoMatch = resolveOnJoinedPlan(Seq(col(SOURCE_ROW_PRESENT_COL).isNull.expr)).head,
      sourceRowHasNoMatch = resolveOnJoinedPlan(Seq(col(TARGET_ROW_PRESENT_COL).isNull.expr)).head,
      matchedConditions = matchedClauses.map(clauseCondition),
      matchedOutputs = matchedClauses.map(matchedClauseOutput),
      notMatchedConditions = notMatchedClauses.map(clauseCondition),
      notMatchedOutputs = notMatchedClauses.map(notMatchedClauseOutput),
      noopCopyOutput =
        resolveOnJoinedPlan(targetOutputCols :+ Literal(false) :+ incrNoopCountExpr),
      deleteRowOutput =
        resolveOnJoinedPlan(targetOutputCols :+ Literal(true) :+ Literal(true)),
      joinedAttributes = joinedPlan.output,
      joinedRowEncoder = joinedRowEncoder,
      outputRowEncoder = outputRowEncoder)

    val outputDF =
      Dataset.ofRows(spark, joinedPlan).mapPartitions(processor.processPartition)(outputRowEncoder)
    logDebug("writeAllChanges: join output plan:\n" + outputDF.queryExecution)

    // Write to Delta
    val newFiles = deltaTxn
      .writeFiles(repartitionIfNeeded(spark, outputDF, deltaTxn.metadata.partitionColumns))
    metrics("numTargetFilesAdded") += newFiles.size
    newFiles
  }


  /**
   * Build a new logical plan using the given `files` that has the same output columns (exprIds)
   * as the `target` logical plan, so that existing update/insert expressions can be applied
   * on this new plan.
   */
  private def buildTargetPlanWithFiles(
    deltaTxn: OptimisticTransaction,
    files: Seq[AddFile]): LogicalPlan = {
    val plan = deltaTxn.deltaLog.createDataFrame(deltaTxn.snapshot, files).queryExecution.analyzed

    // For each plan output column, find the corresponding target output column (by name) and
    // create an alias
    val aliases = plan.output.map {
      case newAttrib: AttributeReference =>
        val existingTargetAttrib = getTargetOutputCols(deltaTxn).find(_.name == newAttrib.name)
          .getOrElse {
            throw new AnalysisException(
              s"Could not find ${newAttrib.name} among the existing target output " +
                s"${getTargetOutputCols(deltaTxn)}")
          }.asInstanceOf[AttributeReference]
        Alias(newAttrib, existingTargetAttrib.name)(exprId = existingTargetAttrib.exprId)
    }
    Project(aliases, plan)
  }

  /** Expressions to increment SQL metrics */
  private def makeMetricUpdateUDF(name: String): Expression = {
    // only capture the needed metric in a local variable
    val metric = metrics(name)
    udf { () => { metric += 1; true }}.asNondeterministic().apply().expr
  }

  private def seqToString(exprs: Seq[Expression]): String = exprs.map(_.sql).mkString("\n\t")

  private def getTargetOutputCols(txn: OptimisticTransaction): Seq[NamedExpression] = {
    txn.metadata.schema.map { col =>
      target.output.find(attr => conf.resolver(attr.name, col.name)).getOrElse {
        Alias(Literal(null, col.dataType), col.name)()
      }
    }
  }

  /**
   * Repartitions the output DataFrame by the partition columns if table is partitioned
   * and `merge.repartitionBeforeWrite.enabled` is set to true.
   */
  protected def repartitionIfNeeded(
      spark: SparkSession,
      df: DataFrame,
      partitionColumns: Seq[String]): DataFrame = {
    if (partitionColumns.nonEmpty && spark.conf.get(DeltaSQLConf.MERGE_REPARTITION_BEFORE_WRITE)) {
      df.repartition(partitionColumns.map(col): _*)
    } else {
      df
    }
  }
}

object MergeIntoCommand {
  /**
   * Spark UI will track all normal accumulators along with Spark tasks to show them on Web UI.
   * However, the accumulator used by `MergeIntoCommand` can store a very large value since it
   * tracks all files that need to be rewritten. We should ask Spark UI to not remember it,
   * otherwise, the UI data may consume lots of memory. Hence, we use the prefix `internal.metrics.`
   * to make this accumulator become an internal accumulator, so that it will not be tracked by
   * Spark UI.
   */
  val TOUCHED_FILES_ACCUM_NAME = "internal.metrics.MergeIntoDelta.touchedFiles"

  val ROW_ID_COL = "_row_id_"
  val FILE_NAME_COL = "_file_name_"
  val SOURCE_ROW_PRESENT_COL = "_source_row_present_"
  val TARGET_ROW_PRESENT_COL = "_target_row_present_"

  class JoinedRowProcessor(
      targetRowHasNoMatch: Expression,
      sourceRowHasNoMatch: Expression,
      matchedConditions: Seq[Expression],
      matchedOutputs: Seq[Seq[Expression]],
      notMatchedConditions: Seq[Expression],
      notMatchedOutputs: Seq[Seq[Expression]],
      noopCopyOutput: Seq[Expression],
      deleteRowOutput: Seq[Expression],
      joinedAttributes: Seq[Attribute],
      joinedRowEncoder: ExpressionEncoder[Row],
      outputRowEncoder: ExpressionEncoder[Row]) extends Serializable {

    private def generateProjection(exprs: Seq[Expression]): UnsafeProjection = {
      UnsafeProjection.create(exprs, joinedAttributes)
    }

    private def generatePredicate(expr: Expression): BasePredicate = {
      GeneratePredicate.generate(expr, joinedAttributes)
    }

    def processPartition(rowIterator: Iterator[Row]): Iterator[Row] = {

      val targetRowHasNoMatchPred = generatePredicate(targetRowHasNoMatch)
      val sourceRowHasNoMatchPred = generatePredicate(sourceRowHasNoMatch)
      val matchedPreds = matchedConditions.map(generatePredicate)
      val matchedProjs = matchedOutputs.map(generateProjection)
      val notMatchedPreds = notMatchedConditions.map(generatePredicate)
      val notMatchedProjs = notMatchedOutputs.map(generateProjection)
      val noopCopyProj = generateProjection(noopCopyOutput)
      val deleteRowProj = generateProjection(deleteRowOutput)
      val outputProj = UnsafeProjection.create(outputRowEncoder.schema)

      def shouldDeleteRow(row: InternalRow): Boolean =
        row.getBoolean(outputRowEncoder.schema.fields.size)

      def processRow(inputRow: InternalRow): InternalRow = {
        if (targetRowHasNoMatchPred.eval(inputRow)) {
          // Target row did not match any source row, so just copy it to the output
          noopCopyProj.apply(inputRow)
        } else {
          // identify which set of clauses to execute: matched or not-matched ones
          val (predicates, projections, noopAction) = if (sourceRowHasNoMatchPred.eval(inputRow)) {
            // Source row did not match with any target row, so insert the new source row
            (notMatchedPreds, notMatchedProjs, deleteRowProj)
          } else {
            // Source row matched with target row, so update the target row
            (matchedPreds, matchedProjs, noopCopyProj)
          }

          // find (predicate, projection) pair whose predicate satisfies inputRow
          val pair = (predicates zip projections).find {
            case (predicate, _) => predicate.eval(inputRow)
          }

          pair match {
            case Some((_, projections)) => projections.apply(inputRow)
            case None => noopAction.apply(inputRow)
          }
        }
      }

      val toRow = joinedRowEncoder.createSerializer()
      val fromRow = outputRowEncoder.createDeserializer()
      rowIterator
        .map(toRow)
        .map(processRow)
        .filter(!shouldDeleteRow(_))
        .map { notDeletedInternalRow =>
          fromRow(outputProj(notDeletedInternalRow))
        }
    }
  }
}
