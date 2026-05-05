package chess.server.persistence

import chess.adapter.repository.mongo.{
  MongoCollectionNames,
  MongoGameRepository,
  MongoGameSchema,
  MongoSessionGameStore,
  MongoSessionMigrationReader,
  MongoSessionRepository,
  MongoSessionSchema
}
import chess.server.config.MongoConfig
import com.mongodb.client.{MongoClient, MongoClients}
import org.bson.Document

import scala.util.control.NonFatal

object MongoPersistenceRuntime:

  final case class Components(
      client: MongoClient,
      reader: MongoSessionMigrationReader,
      sessionRepository: MongoSessionRepository,
      gameRepository: MongoGameRepository,
      store: MongoSessionGameStore
  ):
    def close(): Unit =
      client.close()

  def open(config: MongoConfig): Either[String, Components] =
    try
      val client = MongoClients.create(config.uri)
      try
        val database = client.getDatabase(config.databaseName)
        val sessions = database.getCollection(MongoCollectionNames.Sessions, classOf[Document])
        val games = database.getCollection(MongoCollectionNames.Games, classOf[Document])

        val initialized = for
          _ <- MongoSessionSchema.initialize(sessions).left.map(_.toString)
          _ <- MongoGameSchema.initialize(games).left.map(_.toString)
        yield
          val sessionRepository = MongoSessionRepository(sessions)
          val gameRepository = MongoGameRepository(games)
          Components(
            client = client,
            reader = MongoSessionMigrationReader(sessions),
            sessionRepository = sessionRepository,
            gameRepository = gameRepository,
            store = MongoSessionGameStore(sessionRepository, gameRepository)
          )
        initialized.left.map { error =>
          client.close()
          error
        }
      catch case NonFatal(error) =>
        client.close()
        Left(safeMessage(error))
    catch case NonFatal(error) => Left(safeMessage(error))

  private def safeMessage(error: Throwable): String =
    Option(error.getMessage)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(error.getClass.getSimpleName)
