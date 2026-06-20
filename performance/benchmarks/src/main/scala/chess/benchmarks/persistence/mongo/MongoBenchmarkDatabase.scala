package chess.benchmarks.persistence.mongo

import chess.adapter.repository.mongo.{
  MongoCollectionNames,
  MongoGameRepository,
  MongoGameSchema,
  MongoSessionRepository,
  MongoSessionSchema
}
import com.mongodb.client.MongoClients
import org.bson.Document
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName

import java.util.UUID

private[mongo] final class SearchessBenchmarkMongoContainer
    extends MongoDBContainer(DockerImageName.parse("mongo:7"))

private[mongo] final case class MongoBenchmarkRepositories(
    gameRepository: MongoGameRepository,
    sessionRepository: MongoSessionRepository
)

private[mongo] final class MongoBenchmarkDatabase:
  private val container = SearchessBenchmarkMongoContainer()
  private var closeAction: Option[() => Unit] = None

  def start(): MongoBenchmarkRepositories =
    container.start()
    val client = MongoClients.create(container.getConnectionString)
    val databaseName = s"searchess_bench_${UUID.randomUUID().toString.replace("-", "")}"
    val database = client.getDatabase(databaseName)
    val gameCollection = database.getCollection(MongoCollectionNames.Games, classOf[Document])
    val sessionCollection = database.getCollection(MongoCollectionNames.Sessions, classOf[Document])

    requireRight(MongoGameSchema.initialize(gameCollection))
    requireRight(MongoSessionSchema.initialize(sessionCollection))
    warmConnection(gameCollection)

    closeAction = Some { () =>
      try database.drop()
      finally client.close()
    }

    MongoBenchmarkRepositories(
      gameRepository = MongoGameRepository(gameCollection),
      sessionRepository = MongoSessionRepository(sessionCollection)
    )

  def stop(): Unit =
    closeAction.foreach(_.apply())
    closeAction = None
    container.stop()

  private def warmConnection(collection: com.mongodb.client.MongoCollection[Document]): Unit =
    collection.countDocuments()

  private def requireRight[A](value: Either[?, A]): A =
    value.fold(error => throw IllegalStateException(error.toString), identity)
