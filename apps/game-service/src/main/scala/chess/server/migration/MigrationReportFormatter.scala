package chess.server.migration

import chess.application.migration.*

object MigrationReportFormatter:
  private val ItemPreviewLimit = 20

  def format(report: MigrationReport): String =
    formatText(report)

  def format(report: MigrationReport, reportFormat: ReportFormat): String =
    reportFormat match
      case ReportFormat.Text => formatText(report)
      case ReportFormat.Json => formatJson(report)

  private def formatText(report: MigrationReport): String =
    val preview = report.itemResults.take(ItemPreviewLimit).map(itemToText)
    val previewBlock =
      if preview.isEmpty then "Item results: none"
      else
        ("Item results:" :: preview.map(value => s"- $value")).mkString(System.lineSeparator())

    List(
      s"Run ID: ${report.runId.value}",
      s"Mode: ${report.mode}",
      s"Source: ${report.sourceAdapterName}",
      s"Target: ${report.targetAdapterName}",
      s"Batch count: ${report.batchCount}",
      s"Scanned sessions: ${report.sourceSessionCount}",
      s"Migrated: ${report.migratedCount}",
      s"Skipped equivalent: ${report.skippedEquivalentCount}",
      s"Would migrate: ${countWouldMigrate(report)}",
      s"Conflicts: ${report.conflictCount}",
      s"Validation mismatches: ${report.validationMismatchCount}",
      s"Validated equivalent: ${report.validatedEquivalentCount}",
      s"Source data missing: ${report.sourceDataMissingCount}",
      s"Write failures: ${report.writeFailureCount}",
      s"Read failures: ${countReadFailures(report)}",
      s"Fatal failure: ${report.fatalFailure.map(_.toString).getOrElse("none")}",
      previewBlock
    ).mkString(System.lineSeparator())

  private def formatJson(report: MigrationReport): String =
    val itemResults = report.itemResults.take(ItemPreviewLimit).map(itemToJson).mkString(",")
    s"""{"runId":${jsonString(report.runId.value)},"mode":${jsonString(report.mode.toString)},"source":${jsonString(report.sourceAdapterName)},"target":${jsonString(report.targetAdapterName)},"batchCount":${report.batchCount},"scannedSessions":${report.sourceSessionCount},"migrated":${report.migratedCount},"skippedEquivalent":${report.skippedEquivalentCount},"wouldMigrate":${countWouldMigrate(report)},"conflicts":${report.conflictCount},"validationMismatches":${report.validationMismatchCount},"validatedEquivalent":${report.validatedEquivalentCount},"sourceDataMissing":${report.sourceDataMissingCount},"writeFailures":${report.writeFailureCount},"readFailures":${countReadFailures(report)},"fatalFailure":${report.fatalFailure.map(value => jsonString(value.toString)).getOrElse("null")},"itemResultsPreview":[$itemResults]}"""

  private def countWouldMigrate(report: MigrationReport): Int =
    report.itemResults.count:
      case MigrationItemResult.WouldMigrate(_, _) => true
      case _                                      => false

  private def countReadFailures(report: MigrationReport): Int =
    report.itemResults.count:
      case MigrationItemResult.ReadFailed(_, _, _, _) => true
      case _                                          => false

  private def itemToText(result: MigrationItemResult): String =
    result match
      case MigrationItemResult.WouldMigrate(sessionId, gameId) =>
        s"WouldMigrate(sessionId=$sessionId, gameId=$gameId)"
      case MigrationItemResult.Migrated(sessionId, gameId) =>
        s"Migrated(sessionId=$sessionId, gameId=$gameId)"
      case MigrationItemResult.SkippedEquivalent(sessionId, gameId) =>
        s"SkippedEquivalent(sessionId=$sessionId, gameId=$gameId)"
      case MigrationItemResult.ValidatedEquivalent(sessionId, gameId) =>
        s"ValidatedEquivalent(sessionId=$sessionId, gameId=$gameId)"
      case MigrationItemResult.Conflict(sessionId, gameId, reason) =>
        s"Conflict(sessionId=$sessionId, gameId=$gameId, reason=$reason)"
      case MigrationItemResult.ValidationMismatch(sessionId, gameId, reason) =>
        s"ValidationMismatch(sessionId=$sessionId, gameId=$gameId, reason=$reason)"
      case MigrationItemResult.SourceGameStateMissing(sessionId, gameId) =>
        s"SourceGameStateMissing(sessionId=$sessionId, gameId=$gameId)"
      case MigrationItemResult.TargetWriteFailed(sessionId, gameId, message) =>
        s"TargetWriteFailed(sessionId=$sessionId, gameId=$gameId, message=$message)"
      case MigrationItemResult.ReadFailed(sessionId, gameId, phase, message) =>
        s"ReadFailed(sessionId=$sessionId, gameId=$gameId, phase=$phase, message=$message)"

  private def itemToJson(result: MigrationItemResult): String =
    result match
      case MigrationItemResult.WouldMigrate(sessionId, gameId) =>
        s"""{"type":"WouldMigrate","sessionId":${jsonString(sessionId.toString)},"gameId":${jsonString(gameId.toString)}}"""
      case MigrationItemResult.Migrated(sessionId, gameId) =>
        s"""{"type":"Migrated","sessionId":${jsonString(sessionId.toString)},"gameId":${jsonString(gameId.toString)}}"""
      case MigrationItemResult.SkippedEquivalent(sessionId, gameId) =>
        s"""{"type":"SkippedEquivalent","sessionId":${jsonString(sessionId.toString)},"gameId":${jsonString(gameId.toString)}}"""
      case MigrationItemResult.ValidatedEquivalent(sessionId, gameId) =>
        s"""{"type":"ValidatedEquivalent","sessionId":${jsonString(sessionId.toString)},"gameId":${jsonString(gameId.toString)}}"""
      case MigrationItemResult.Conflict(sessionId, gameId, reason) =>
        s"""{"type":"Conflict","sessionId":${jsonString(sessionId.toString)},"gameId":${jsonString(gameId.toString)},"reason":${jsonString(reason)}}"""
      case MigrationItemResult.ValidationMismatch(sessionId, gameId, reason) =>
        s"""{"type":"ValidationMismatch","sessionId":${jsonString(sessionId.toString)},"gameId":${jsonString(gameId.toString)},"reason":${jsonString(reason)}}"""
      case MigrationItemResult.SourceGameStateMissing(sessionId, gameId) =>
        s"""{"type":"SourceGameStateMissing","sessionId":${jsonString(sessionId.toString)},"gameId":${jsonString(gameId.toString)}}"""
      case MigrationItemResult.TargetWriteFailed(sessionId, gameId, message) =>
        s"""{"type":"TargetWriteFailed","sessionId":${jsonString(sessionId.toString)},"gameId":${jsonString(gameId.toString)},"message":${jsonString(message)}}"""
      case MigrationItemResult.ReadFailed(sessionId, gameId, phase, message) =>
        s"""{"type":"ReadFailed","sessionId":${jsonString(sessionId.toString)},"gameId":${jsonString(gameId.toString)},"phase":${jsonString(phase.toString)},"message":${jsonString(message)}}"""

  private def jsonString(value: String): String =
    "\"" + value.flatMap:
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\b' => "\\b"
      case '\f' => "\\f"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case ch if ch.isControl => f"\\u${ch.toInt}%04x"
      case ch => ch.toString
    + "\""
