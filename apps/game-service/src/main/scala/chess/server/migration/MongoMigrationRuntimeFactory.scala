package chess.server.migration

import chess.server.persistence.MongoPersistenceRuntime

import scala.util.control.NonFatal

object MongoMigrationRuntimeFactory:
  def withRuntime[A](
      config: MigrationConfigLoader.MongoConfig
  )(
      use: MigrationRuntimeFactory.BackendRuntime => A
  ): Either[String, A] =
    MongoPersistenceRuntime.open(config).flatMap { runtime =>
      try
        Right(
          use(
            MigrationRuntimeFactory.BackendRuntime(
              runtime.reader,
              runtime.sessionRepository,
              runtime.gameRepository,
              runtime.store
            )
          )
        )
      catch case NonFatal(error) => Left(safeMessage(error))
      finally runtime.close()
    }

  private def safeMessage(error: Throwable): String =
    Option(error.getMessage)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(error.getClass.getSimpleName)
