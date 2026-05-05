package chess.server.migration

import chess.application.migration.*

object MigrationCliRunner:

  /** Execute a migration and return the structured report without printing or formatting.
    *
    * Used by [[chess.server.http.MigrationAdminRoutes]] so the HTTP layer can format and return
    * the report as JSON without duplicating runtime factory or service logic.
    */
  def runForReport(command: MigrationCommand): Either[String, MigrationReport] =
    val service = new PersistenceMigrationService()
    MigrationRuntimeFactory.withRuntime(command.source) { sourceRuntime =>
      MigrationRuntimeFactory.withRuntime(command.target) { targetRuntime =>
        val source = MigrationSourceAdapter(
          name = command.source.entryName,
          sessionReader = sourceRuntime.reader,
          gameRepository = sourceRuntime.gameRepository
        )
        val target = MigrationTargetAdapter(
          name = command.target.entryName,
          sessionRepository = targetRuntime.sessionRepository,
          gameRepository = targetRuntime.gameRepository,
          store = targetRuntime.store
        )
        val executionReport = runMigration(service, source, target, command.mode, command.batchSize)
        MigrationExecutionWorkflow.finalReport(command, executionReport) {
          runMigration(service, source, target, MigrationMode.ValidateOnly, command.batchSize)
        }
      }
    }.flatMap(identity)

  def run(command: MigrationCommand): Int =
    val service = new PersistenceMigrationService()

    MigrationRuntimeFactory.withRuntime(command.source) { sourceRuntime =>
      MigrationRuntimeFactory.withRuntime(command.target) { targetRuntime =>
        val source = MigrationSourceAdapter(
          name = command.source.entryName,
          sessionReader = sourceRuntime.reader,
          gameRepository = sourceRuntime.gameRepository
        )
        val target = MigrationTargetAdapter(
          name = command.target.entryName,
          sessionRepository = targetRuntime.sessionRepository,
          gameRepository = targetRuntime.gameRepository,
          store = targetRuntime.store
        )
        val executionReport = runMigration(service, source, target, command.mode, command.batchSize)
        val report = MigrationExecutionWorkflow.finalReport(command, executionReport) {
          runMigration(service, source, target, MigrationMode.ValidateOnly, command.batchSize)
        }

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

  private def runMigration(
      service: PersistenceMigrationService,
      source: MigrationSourceAdapter,
      target: MigrationTargetAdapter,
      mode: MigrationMode,
      batchSize: Int
  ): MigrationReport =
    service.run(
      source = source,
      target = target,
      mode = mode,
      batchSize = batchSize,
      conflictPolicy = MigrationConflictPolicy.SkipEquivalentElseConflict
    )
