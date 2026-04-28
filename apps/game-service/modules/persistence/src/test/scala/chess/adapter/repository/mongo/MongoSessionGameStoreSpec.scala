package chess.adapter.repository.mongo

import chess.adapter.repository.contract.SessionGameStoreContract
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec

import java.util.UUID

/** Contract coverage for the Mongo [[chess.application.port.repository.SessionGameStore]] adapter.
  *
  * MongoSessionGameStore is adapter-compatible with SessionGameStore for successful writes and
  * session-side conflicts, but it is not guarantee-equivalent to PostgresSessionGameStore:
  * Postgres uses a database transaction, while this Mongo adapter coordinates the session and
  * game-state repositories sequentially.
  */
class MongoSessionGameStoreSpec
    extends AnyFlatSpec
    with SessionGameStoreContract
    with BeforeAndAfterAll:

  private val databaseName: String =
    s"searchess_session_game_contract_${UUID.randomUUID().toString.replace("-", "")}"

  private lazy val client: Option[com.mongodb.client.MongoClient] =
    MongoSessionGameStoreSpec.config.map(config => com.mongodb.client.MongoClients.create(config.uri))

  override def storeName: String =
    "MongoSessionGameStore best-effort adapter"

  override def freshStore(): StoreFixture =
    val mongoClient = client.getOrElse(
      cancel("Set SEARCHESS_MONGO_URI to run the Mongo SessionGameStore contract tests")
    )
    val database = mongoClient.getDatabase(databaseName)
    database.drop()

    val sessionCollection = database.getCollection("sessions")
    val gameCollection = database.getCollection("game_states")

    MongoSessionSchema.initialize(sessionCollection).fold(
      error => fail(s"Could not initialize Mongo session collection: $error"),
      _ => ()
    )
    MongoGameSchema.initialize(gameCollection).fold(
      error => fail(s"Could not initialize Mongo game-state collection: $error"),
      _ => ()
    )

    val sessionRepository = MongoSessionRepository(sessionCollection)
    val gameRepository = MongoGameRepository(gameCollection)
    StoreFixture(
      sessionRepository = sessionRepository,
      gameRepository = gameRepository,
      store = MongoSessionGameStore(sessionRepository, gameRepository)
    )

  override protected def afterAll(): Unit =
    client.foreach { mongoClient =>
      mongoClient.getDatabase(databaseName).drop()
      mongoClient.close()
    }
    super.afterAll()

private object MongoSessionGameStoreSpec:

  final case class Config(uri: String)

  def config: Option[Config] =
    sys.env.get("SEARCHESS_MONGO_URI").map(Config(_))
