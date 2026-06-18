package chess.server.migration

import chess.application.migration.*
import chess.application.session.model.SessionIds.{GameId, SessionId}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID

class MigrationReportFormatterSpec extends AnyFlatSpec with Matchers:

  "MigrationReportFormatter" should "format the structured text report fields" in {
    val report = MigrationReport.fromItems(
      mode = MigrationMode.Execute,
      sourceAdapterName = "postgres",
      targetAdapterName = "mongo",
      startedAt = Instant.parse("2026-04-26T12:00:00Z"),
      finishedAt = Instant.parse("2026-04-26T12:00:03Z"),
      batchSize = 50,
      batchCount = 1,
      itemResults = List(MigrationItemResult.Migrated(sessionId, gameId)),
      fatalFailure = None,
      runId = MigrationRunId("run-1")
    )

    val text = MigrationReportFormatter.format(report, ReportFormat.Text)

    text should include("Migration report")
    text should include("Status: Success")
    text should include("Run ID: run-1")
    text should include("Source store: postgres")
    text should include("Target store: mongo")
    text should include("Mode: Execute")
    text should include("Started at: 2026-04-26T12:00:00Z")
    text should include("Finished at: 2026-04-26T12:00:03Z")
    text should include("Duration: PT3S")
    text should include("Batch size: 50")
    text should include("Scanned: 1")
    text should include("Migrated: 1")
    text should include("Failed: 0")
    text should include("Validation ran: false")
    text should include("Validation result: not applicable")
  }

  it should "format JSON report fields when requested" in {
    val report = MigrationReport.fromItems(
      mode = MigrationMode.ValidateOnly,
      sourceAdapterName = "mongo",
      targetAdapterName = "postgres",
      startedAt = Instant.parse("2026-04-26T12:00:00Z"),
      finishedAt = Instant.parse("2026-04-26T12:00:00Z"),
      batchSize = 25,
      batchCount = 1,
      itemResults = List(
        MigrationItemResult.ValidationMismatch(sessionId, gameId, "Target aggregate is missing")
      ),
      fatalFailure = None,
      runId = MigrationRunId("run-2")
    )

    val json = MigrationReportFormatter.format(report, ReportFormat.Json)

    json should include(""""runId":"run-2"""")
    json should include(""""source":"mongo"""")
    json should include(""""target":"postgres"""")
    json should include(""""sourceStore":"mongo"""")
    json should include(""""targetStore":"postgres"""")
    json should include(""""mode":"ValidateOnly"""")
    json should include(""""batchSize":25""")
    json should include(""""scannedSessions":1""")
    json should include(""""scanned":1""")
    json should include(""""validationRan":true""")
    json should include(""""validationResult":"Failed"""")
    json should include(""""status":"CompletedWithConflicts"""")
  }

  it should "format validation-after-execute results in text reports" in {
    val executionReport = MigrationReport.fromItems(
      mode = MigrationMode.Execute,
      sourceAdapterName = "postgres",
      targetAdapterName = "mongo",
      startedAt = Instant.parse("2026-04-26T12:00:00Z"),
      finishedAt = Instant.parse("2026-04-26T12:00:01Z"),
      batchSize = 50,
      batchCount = 1,
      itemResults = List(MigrationItemResult.Migrated(sessionId, gameId)),
      fatalFailure = None,
      runId = MigrationRunId("run-3")
    )
    val validationReport = MigrationReport.fromItems(
      mode = MigrationMode.ValidateOnly,
      sourceAdapterName = "postgres",
      targetAdapterName = "mongo",
      startedAt = Instant.parse("2026-04-26T12:00:01Z"),
      finishedAt = Instant.parse("2026-04-26T12:00:02Z"),
      batchSize = 50,
      batchCount = 1,
      itemResults = List(MigrationItemResult.ValidatedEquivalent(sessionId, gameId)),
      fatalFailure = None,
      runId = MigrationRunId("run-4")
    )

    val text = MigrationReportFormatter.format(
      executionReport.withValidationReport(validationReport),
      ReportFormat.Text
    )

    text should include("Mode: Execute")
    text should include("Validation ran: true")
    text should include("Validation result: Passed")
    text should include("Validated equivalent: 1")
  }

  private val sessionId = SessionId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val gameId = GameId(UUID.fromString("10000000-0000-0000-0000-000000000001"))
