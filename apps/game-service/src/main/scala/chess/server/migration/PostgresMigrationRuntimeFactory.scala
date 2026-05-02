package chess.server.migration

import chess.adapter.repository.postgres.PostgresPersistenceRuntime

import scala.util.control.NonFatal

object PostgresMigrationRuntimeFactory:
  def withRuntime[A](
      config: MigrationConfigLoader.PostgresConfig
  )(
      use: MigrationRuntimeFactory.BackendRuntime => A
  ): Either[String, A] =
    PostgresPersistenceRuntime.open(config.url, config.user, config.password).flatMap { runtime =>
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
