package chess.server.migration

import chess.application.migration.MigrationReport

object MigrationExitCode:

  def fromReport(report: MigrationReport): Int =
    val unsafe =
      report.fatalFailure.nonEmpty ||
        report.conflictCount > 0 ||
        report.validationMismatchCount > 0 ||
        report.sourceDataMissingCount > 0 ||
        report.writeFailureCount > 0 ||
        report.readerFailureCount > 0 ||
        report.storageFailureCount > 0

    if unsafe then 2 else 0