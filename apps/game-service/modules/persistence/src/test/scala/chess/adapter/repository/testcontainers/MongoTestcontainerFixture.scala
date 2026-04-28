package chess.adapter.repository.testcontainers

import chess.adapter.repository.mongo.{
  MongoGameRepository,
  MongoGameSchema,
  MongoCollectionNames,
  MongoSessionGameStore,
  MongoSessionMigrationReader,
  MongoSessionRepository,
  MongoSessionSchema
}
import chess.application.migration.SessionMigrationReader
import chess.application.port.repository.{GameRepository, SessionGameStore, SessionRepository}
import com.mongodb.client.MongoClients
import org.bson.Document
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName

import java.util.UUID
import scala.collection.mutable.ListBuffer

final class SearchessMongoContainer
    extends MongoDBContainer(DockerImageName.parse("mongo:7"))

final class MongoTestcontainerFixture:
  private val container = SearchessMongoContainer()
  private val closeActions = ListBuffer.empty[() => Unit]

  def start(): Unit =
    container.start()

  def stop(): Unit =
    closeActions.reverse.foreach(close => close())
    closeActions.clear()
    container.stop()

  def connectionString: String = container.getConnectionString

  def freshStoreParts(): StoreParts =
    val runtime = freshRuntime()
    StoreParts(
      sessionRepository = runtime.sessionRepository,
      gameRepository = runtime.gameRepository,
      store = runtime.store
    )

  def freshRuntime(): MongoRuntime =
    val client = MongoClients.create(connectionString)
    val databaseName = s"searchess_tc_${UUID.randomUUID().toString.replace("-", "")}"
    val database = client.getDatabase(databaseName)
    val sessionCollection = database.getCollection(MongoCollectionNames.Sessions, classOf[Document])
    val gameCollection = database.getCollection(MongoCollectionNames.Games, classOf[Document])

    MongoSessionSchema.initialize(sessionCollection).fold(
      error => sys.error(error.toString),
      identity
    )
    MongoGameSchema.initialize(gameCollection).fold(
      error => sys.error(error.toString),
      identity
    )

    val sessionRepository = MongoSessionRepository(sessionCollection)
    val gameRepository = MongoGameRepository(gameCollection)
    lazy val closeAction: () => Unit =
      () =>
        closeActions -= closeAction
        try database.drop()
        finally client.close()
    closeActions += closeAction
    MongoRuntime(
      closeAction = closeAction,
      reader = MongoSessionMigrationReader(sessionCollection),
      sessionRepository = sessionRepository,
      gameRepository = gameRepository,
      store = MongoSessionGameStore(sessionRepository, gameRepository)
    )

final case class MongoRuntime(
    closeAction: () => Unit,
    reader: SessionMigrationReader,
    sessionRepository: SessionRepository,
    gameRepository: GameRepository,
    store: SessionGameStore
):
  def close(): Unit =
    closeAction()
