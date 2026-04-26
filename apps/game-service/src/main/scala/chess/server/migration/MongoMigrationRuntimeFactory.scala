package chess.server.migration

import chess.adapter.repository.mongo.*
import com.mongodb.client.MongoClients
import org.bson.Document

import scala.util.control.NonFatal

object MongoMigrationRuntimeFactory:
  def withRuntime[A](
      config: MigrationConfigLoader.MongoConfig
  )(
      use: MigrationRuntimeFactory.BackendRuntime => A
  ): Either[String, A] =
    val client = MongoClients.create(config.uri)

    try
      val database = client.getDatabase(config.databaseName)
      val sessions = database.getCollection("sessions", classOf[Document])
      val games = database.getCollection("games", classOf[Document])

      for
        _ <- MongoSessionSchema.initialize(sessions).left.map(_.toString)
        _ <- MongoGameSchema.initialize(games).left.map(_.toString)
      yield
        use(
          MigrationRuntimeFactory.BackendRuntime(
            MongoSessionMigrationReader(sessions),
            MongoSessionRepository(sessions),
            MongoGameRepository(games),
            MongoSessionGameStore(
              MongoSessionRepository(sessions),
              MongoGameRepository(games)
            )
          )
        )
    catch case NonFatal(error) => Left(safeMessage(error))
    finally client.close()

  private def safeMessage(error: Throwable): String =
    Option(error.getMessage)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(error.getClass.getSimpleName)
