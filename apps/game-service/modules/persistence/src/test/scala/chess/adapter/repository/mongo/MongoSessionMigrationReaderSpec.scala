package chess.adapter.repository.mongo

import chess.adapter.migration.contract.SessionMigrationReaderContract
import chess.application.migration.SessionMigrationReader
import chess.application.session.model.GameSession
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec

import java.util.UUID

class MongoSessionMigrationReaderSpec
    extends AnyFlatSpec
    with SessionMigrationReaderContract
    with BeforeAndAfterAll:

  private val databaseName: String =
    s"searchess_session_migration_reader_${UUID.randomUUID().toString.replace("-", "")}"

  private lazy val client: Option[MongoClient] =
    MongoSessionMigrationReaderSpec.config.map(config => MongoClients.create(config.uri))

  override def readerName: String = "MongoSessionMigrationReader"

  override def freshReaderFixture(sessions: List[GameSession]): ReaderFixture =
    val mongoClient = client.getOrElse(
      cancel("Set SEARCHESS_MONGO_URI to run the Mongo SessionMigrationReader tests")
    )
    val database = mongoClient.getDatabase(databaseName)
    database.drop()
    val collection = database.getCollection("sessions")
    MongoSessionSchema.initialize(collection).fold(
      error => fail(s"Could not initialize Mongo session collection: $error"),
      _ => ()
    )
    val repository = MongoSessionRepository(collection)
    sessions.foreach(repository.save(_).fold(error => fail(error.toString), identity))
    ReaderFixture(
      reader = MongoSessionMigrationReader(collection),
      expectedOrder = sessions.sortBy(_.sessionId.value.toString)
    )

  override protected def afterAll(): Unit =
    client.foreach { mongoClient =>
      mongoClient.getDatabase(databaseName).drop()
      mongoClient.close()
    }
    super.afterAll()

private object MongoSessionMigrationReaderSpec:

  final case class Config(uri: String)

  def config: Option[Config] =
    sys.env.get("SEARCHESS_MONGO_URI").map(Config(_))
