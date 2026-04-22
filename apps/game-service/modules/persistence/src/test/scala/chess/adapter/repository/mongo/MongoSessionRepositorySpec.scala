package chess.adapter.repository.mongo

import chess.adapter.repository.contract.SessionRepositoryContract
import chess.application.port.repository.SessionRepository
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec

import java.util.UUID

class MongoSessionRepositorySpec
    extends AnyFlatSpec
    with SessionRepositoryContract
    with BeforeAndAfterAll:

  private val databaseName: String =
    s"searchess_session_contract_${UUID.randomUUID().toString.replace("-", "")}"

  private lazy val client: Option[MongoClient] =
    MongoSessionRepositorySpec.config.map(config => MongoClients.create(config.uri))

  override def repositoryName: String = "MongoSessionRepository"

  override def freshRepository(): SessionRepository =
    val mongoClient = client.getOrElse(
      cancel("Set SEARCHESS_MONGO_URI to run the Mongo SessionRepository contract tests")
    )
    val database = mongoClient.getDatabase(databaseName)
    database.drop()
    val collection = database.getCollection("sessions")
    MongoSessionSchema.initialize(collection).fold(
      error => fail(s"Could not initialize Mongo session collection: $error"),
      _ => MongoSessionRepository(collection)
    )

  override protected def afterAll(): Unit =
    client.foreach { mongoClient =>
      mongoClient.getDatabase(databaseName).drop()
      mongoClient.close()
    }
    super.afterAll()

private object MongoSessionRepositorySpec:

  final case class Config(uri: String)

  def config: Option[Config] =
    sys.env.get("SEARCHESS_MONGO_URI").map(Config(_))
