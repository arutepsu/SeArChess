package chess.server.migration

import chess.application.migration.*

object MigrationExecutionWorkflow:

  def finalReport(
      command: MigrationCommand,
      executionReport: MigrationReport
  )(validate: => MigrationReport): MigrationReport =
    if command.validateAfterExecute &&
        command.mode == MigrationMode.Execute &&
        executionReport.finalStatus == MigrationFinalStatus.Success
    then executionReport.withValidationReport(validate)
    else executionReport
