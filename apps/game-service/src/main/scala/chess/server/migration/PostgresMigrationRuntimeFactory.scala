package chess.server.migration

import chess.adapter.repository.postgres.*
import slick.jdbc.PostgresProfile.api.Database

import scala.util.control.NonFatal

object PostgresMigrationRuntimeFactory:
  def withRuntime[A](
      config: MigrationConfigLoader.PostgresConfig
  )(
      use: MigrationRuntimeFactory.BackendRuntime => A
  ): Either[String, A] =
    try
      PostgresFlywaySchemaInitializer.migrate(
        url = config.url,
        user = config.user,
        password = config.password
      )

      val db =
        Database.forURL(
          url = config.url,
          user = config.user,
          password = config.password,
          driver = "org.postgresql.Driver"
        )

      try
        Right(
          use(
            MigrationRuntimeFactory.BackendRuntime(
              PostgresSessionMigrationReader(db),
              PostgresSessionRepository(db),
              PostgresGameRepository(db),
              PostgresSessionGameStore(db)
            )
          )
        )
      finally db.close()
    catch case NonFatal(error) => Left(safeMessage(error))

  private def safeMessage(error: Throwable): String =
    Option(error.getMessage)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(error.getClass.getSimpleName)
