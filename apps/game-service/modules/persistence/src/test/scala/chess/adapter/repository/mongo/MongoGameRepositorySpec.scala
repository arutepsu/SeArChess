package chess.adapter.repository.mongo

import chess.adapter.repository.contract.GameRepositoryContract
import chess.application.port.repository.GameRepository
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec

import java.util.UUID

class MongoGameRepositorySpec
    extends AnyFlatSpec
    with GameRepositoryContract
    with BeforeAndAfterAll:

  private val databaseName: String =
    s"searchess_game_contract_${UUID.randomUUID().toString.replace("-", "")}"

  private lazy val client: Option[MongoClient] =
    MongoGameRepositorySpec.config.map(config => MongoClients.create(config.uri))

  override def repositoryName: String = "MongoGameRepository"

  override def freshRepository(): GameRepository =
    val mongoClient = client.getOrElse(
      cancel("Set SEARCHESS_MONGO_URI to run the Mongo GameRepository contract tests")
    )
    val database = mongoClient.getDatabase(databaseName)
    database.drop()
    val collection = database.getCollection("game_states")
    MongoGameSchema.initialize(collection).fold(
      error => fail(s"Could not initialize Mongo game-state collection: $error"),
      _ => MongoGameRepository(collection)
    )

  override protected def afterAll(): Unit =
    client.foreach { mongoClient =>
      mongoClient.getDatabase(databaseName).drop()
      mongoClient.close()
    }
    super.afterAll()

private object MongoGameRepositorySpec:

  final case class Config(uri: String)

  def config: Option[Config] =
    sys.env.get("SEARCHESS_MONGO_URI").map(Config(_))
