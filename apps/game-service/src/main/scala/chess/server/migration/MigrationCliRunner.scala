package chess.server.migration

import chess.application.migration.*

object MigrationCliRunner:

  def run(command: MigrationCommand): Int =
    val service = new PersistenceMigrationService()

    MigrationRuntimeFactory.withRuntime(command.source) { sourceRuntime =>
      MigrationRuntimeFactory.withRuntime(command.target) { targetRuntime =>
        val report = service.run(
          source = MigrationSourceAdapter(
            name = command.source.entryName,
            sessionReader = sourceRuntime.reader,
            gameRepository = sourceRuntime.gameRepository
          ),
          target = MigrationTargetAdapter(
            name = command.target.entryName,
            sessionRepository = targetRuntime.sessionRepository,
            gameRepository = targetRuntime.gameRepository,
            store = targetRuntime.store
          ),
          mode = command.mode,
          batchSize = command.batchSize,
          conflictPolicy = MigrationConflictPolicy.SkipEquivalentElseConflict
        )

        println(MigrationReportFormatter.format(report, command.reportFormat))
        MigrationExitCode.fromReport(report)
      }.fold(
        error =>
          Console.err.println(error)
          1,
        identity
      )
    } match
      case Right(exitCode) =>
        exitCode

      case Left(error) =>
        Console.err.println(error)
        1