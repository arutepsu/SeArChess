package chess.server.migration

import chess.application.migration.SessionMigrationReader
import chess.application.port.repository.{GameRepository, SessionGameStore, SessionRepository}

object MigrationRuntimeFactory:

  final case class BackendRuntime(
      reader: SessionMigrationReader,
      sessionRepository: SessionRepository,
      gameRepository: GameRepository,
      store: SessionGameStore
  )

  def withRuntime[A](backend: Backend)(use: BackendRuntime => A): Either[String, A] =
    backend match
      case Backend.Postgres =>
        MigrationConfigLoader
          .loadPostgres()
          .flatMap(PostgresMigrationRuntimeFactory.withRuntime(_)(use))

      case Backend.Mongo =>
        MigrationConfigLoader
          .loadMongo()
          .flatMap(MongoMigrationRuntimeFactory.withRuntime(_)(use))
