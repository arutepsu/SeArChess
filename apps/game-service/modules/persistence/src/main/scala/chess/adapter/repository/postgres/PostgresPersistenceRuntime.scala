package chess.adapter.repository.postgres

import chess.application.migration.SessionMigrationReader
import chess.application.port.repository.{
  ExternalGameBindingRepository,
  ExternalGameCommandStore,
  GameRepository,
  SessionGameStore,
  SessionRepository
}
import slick.jdbc.PostgresProfile.api.Database

import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

object PostgresPersistenceRuntime:

  final case class Components(
      reader: SessionMigrationReader,
      sessionRepository: SessionRepository,
      gameRepository: GameRepository,
      store: SessionGameStore,
      externalGameBindingRepository: ExternalGameBindingRepository,
      externalGameCommandStore: ExternalGameCommandStore,
      close: () => Unit
  )

  def open(
      url: String,
      user: String,
      password: String,
      schema: Option[String] = None,
      timeout: Duration = Duration.Inf
  ): Either[String, Components] =
    try
      PostgresFlywaySchemaInitializer.migrate(
        url = url,
        user = user,
        password = password,
        schema = schema
      )

      val db =
        Database.forURL(
          url = url,
          user = user,
          password = password,
          driver = "org.postgresql.Driver"
        )

      Right(
        Components(
          reader = PostgresSessionMigrationReader(db, timeout, schema),
          sessionRepository = PostgresSessionRepository(db, timeout, schema),
          gameRepository = PostgresGameRepository(db, timeout, schema),
          store = PostgresSessionGameStore(db, timeout, schema),
          externalGameBindingRepository =
            PostgresExternalGameBindingRepository(db, timeout, schema),
          externalGameCommandStore = PostgresExternalGameCommandStore(db, timeout, schema),
          close = () => db.close()
        )
      )
    catch case NonFatal(error) => Left(safeMessage(error))

  private def safeMessage(error: Throwable): String =
    Option(error.getMessage)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(error.getClass.getSimpleName)
