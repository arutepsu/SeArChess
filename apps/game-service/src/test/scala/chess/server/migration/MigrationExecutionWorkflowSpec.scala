package chess.server.migration

import chess.application.migration.*
import chess.application.session.model.SessionIds.{GameId, SessionId}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID

class MigrationExecutionWorkflowSpec extends AnyFlatSpec with Matchers:

  "MigrationExecutionWorkflow" should "leave execute reports unchanged without the flag" in {
    val report = MigrationExecutionWorkflow.finalReport(executeCommand(validate = false), executeReport) {
      fail("validation should not run without the flag")
    }

    report.validationResult shouldBe None
    report.validationRan shouldBe false
    report shouldBe executeReport
  }

  it should "fold successful validation into an execute report when requested" in {
    val report = MigrationExecutionWorkflow.finalReport(executeCommand(validate = true), executeReport) {
      validationPassedReport
    }

    report.mode shouldBe MigrationMode.Execute
    report.migratedCount shouldBe 1
    report.validatedEquivalentCount shouldBe 1
    report.validationRan shouldBe true
    report.validationResult shouldBe Some(MigrationValidationResult.Passed)
    report.finalStatus shouldBe MigrationFinalStatus.Success
  }

  it should "not run validation after failed execution" in {
    val report = MigrationExecutionWorkflow.finalReport(executeCommand(validate = true), failedExecuteReport) {
      fail("validation should not run after failed execution")
    }

    report.finalStatus shouldBe MigrationFinalStatus.Failed
    report.validationResult shouldBe None
    report.validationRan shouldBe false
  }

  it should "represent validation mismatches as completed with conflicts" in {
    val report = MigrationExecutionWorkflow.finalReport(executeCommand(validate = true), executeReport) {
      validationFailedReport
    }

    report.validationResult shouldBe Some(MigrationValidationResult.Failed)
    report.finalStatus shouldBe MigrationFinalStatus.CompletedWithConflicts
  }

  it should "show validation passed even when the validation pass has no items" in {
    val emptyValidationReport = MigrationReport.fromItems(
      mode = MigrationMode.ValidateOnly,
      sourceAdapterName = "postgres",
      targetAdapterName = "mongo",
      startedAt = finishedAt,
      finishedAt = Instant.parse("2026-04-26T12:00:02Z"),
      batchSize = 10,
      batchCount = 1,
      itemResults = Nil,
      fatalFailure = None,
      runId = MigrationRunId("empty-validation-run")
    )

    val report = MigrationExecutionWorkflow.finalReport(executeCommand(validate = true), executeReport) {
      emptyValidationReport
    }

    report.validationRan shouldBe true
    report.validationResult shouldBe Some(MigrationValidationResult.Passed)
  }

  private def executeCommand(validate: Boolean): MigrationCommand =
    MigrationCommand(
      source = Backend.Postgres,
      target = Backend.Mongo,
      mode = MigrationMode.Execute,
      batchSize = 10,
      validateAfterExecute = validate
    )

  private val startedAt = Instant.parse("2026-04-26T12:00:00Z")
  private val finishedAt = Instant.parse("2026-04-26T12:00:01Z")
  private val sessionId = SessionId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val gameId = GameId(UUID.fromString("10000000-0000-0000-0000-000000000001"))

  private val executeReport = MigrationReport.fromItems(
    mode = MigrationMode.Execute,
    sourceAdapterName = "postgres",
    targetAdapterName = "mongo",
    startedAt = startedAt,
    finishedAt = finishedAt,
    batchSize = 10,
    batchCount = 1,
    itemResults = List(MigrationItemResult.Migrated(sessionId, gameId)),
    fatalFailure = None,
    runId = MigrationRunId("execute-run")
  )

  private val failedExecuteReport = MigrationReport.fromItems(
    mode = MigrationMode.Execute,
    sourceAdapterName = "postgres",
    targetAdapterName = "mongo",
    startedAt = startedAt,
    finishedAt = finishedAt,
    batchSize = 10,
    batchCount = 1,
    itemResults = List(MigrationItemResult.TargetWriteFailed(sessionId, gameId, "boom")),
    fatalFailure = None,
    runId = MigrationRunId("failed-execute-run")
  )

  private val validationPassedReport = MigrationReport.fromItems(
    mode = MigrationMode.ValidateOnly,
    sourceAdapterName = "postgres",
    targetAdapterName = "mongo",
    startedAt = finishedAt,
    finishedAt = Instant.parse("2026-04-26T12:00:02Z"),
    batchSize = 10,
    batchCount = 1,
    itemResults = List(MigrationItemResult.ValidatedEquivalent(sessionId, gameId)),
    fatalFailure = None,
    runId = MigrationRunId("validation-run")
  )

  private val validationFailedReport = MigrationReport.fromItems(
    mode = MigrationMode.ValidateOnly,
    sourceAdapterName = "postgres",
    targetAdapterName = "mongo",
    startedAt = finishedAt,
    finishedAt = Instant.parse("2026-04-26T12:00:02Z"),
    batchSize = 10,
    batchCount = 1,
    itemResults = List(MigrationItemResult.ValidationMismatch(sessionId, gameId, "Target differs")),
    fatalFailure = None,
    runId = MigrationRunId("validation-run")
  )
