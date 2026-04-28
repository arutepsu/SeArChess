package chess.adapter.repository.mongo

import chess.adapter.migration.contract.SessionMigrationReaderContract
import chess.application.migration.{SessionMigrationBatch, SessionMigrationCursor, SessionMigrationReader}
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec

import java.time.Instant
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
    val collection = database.getCollection(MongoCollectionNames.Sessions)
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

  it should "complete traversal when there are no records" in {
    val fixture = freshReaderFixture(Nil)

    readAll(fixture.reader, batchSize = 2) shouldBe Nil
  }

  it should "not produce a false next cursor when record count is less than batchSize" in {
    val sessions = generatedSessions(count = 1)
    val fixture = freshReaderFixture(sessions)
    val batch = fixture.reader.readBatch(None, batchSize = 2).value

    batch.sessions shouldBe fixture.expectedOrder
    batch.nextCursor shouldBe None
    readAll(fixture.reader, batchSize = 2) shouldBe fixture.expectedOrder
  }

  it should "not produce a false next cursor when record count is exactly batchSize" in {
    val sessions = generatedSessions(count = 2)
    val fixture = freshReaderFixture(sessions)
    val batch = fixture.reader.readBatch(None, batchSize = 2).value

    batch.sessions shouldBe fixture.expectedOrder
    batch.nextCursor shouldBe None
    readAll(fixture.reader, batchSize = 2) shouldBe fixture.expectedOrder
  }

  it should "not produce a false next cursor when record count is exactly two full batches" in {
    val sessions = generatedSessions(count = 4)
    val fixture = freshReaderFixture(sessions)
    val batches = readBatches(fixture.reader, batchSize = 2)

    batches.map(_.sessions.size) shouldBe List(2, 2)
    batches.last.nextCursor shouldBe None
    batches.flatMap(_.sessions) shouldBe fixture.expectedOrder
  }

  it should "produce a real next cursor when record count is two full batches plus one" in {
    val sessions = generatedSessions(count = 5)
    val fixture = freshReaderFixture(sessions)
    val batches = readBatches(fixture.reader, batchSize = 2)

    batches.map(_.sessions.size) shouldBe List(2, 2, 1)
    batches.init.foreach(_.nextCursor should not be empty)
    batches.last.nextCursor shouldBe None
    batches.flatMap(_.sessions) shouldBe fixture.expectedOrder
  }

  override protected def afterAll(): Unit =
    client.foreach { mongoClient =>
      mongoClient.getDatabase(databaseName).drop()
      mongoClient.close()
    }
    super.afterAll()

  private def generatedSessions(count: Int): List[GameSession] =
    (1 to count).toList.map { index =>
      GameSession(
        sessionId = SessionId(UUID.fromString(f"00000000-0000-0000-0000-$index%012d")),
        gameId = GameId(UUID.fromString(f"10000000-0000-0000-0000-$index%012d")),
        mode = SessionMode.HumanVsHuman,
        whiteController = SideController.HumanLocal,
        blackController = SideController.HumanRemote,
        lifecycle = SessionLifecycle.Active,
        createdAt = Instant.parse("2024-01-01T00:00:00Z").plusSeconds(index.toLong),
        updatedAt = Instant.parse("2024-01-01T00:10:00Z").plusSeconds(index.toLong)
      )
    }

  private def readAll(reader: SessionMigrationReader, batchSize: Int): List[GameSession] =
    readBatches(reader, batchSize).flatMap(_.sessions)

  private def readBatches(
      reader: SessionMigrationReader,
      batchSize: Int
  ): List[SessionMigrationBatch] =
    def loop(
        cursor: Option[SessionMigrationCursor],
        acc: List[SessionMigrationBatch]
    ): List[SessionMigrationBatch] =
      reader.readBatch(cursor, batchSize).value match
        case batch if batch.nextCursor.isEmpty => acc :+ batch
        case batch                             => loop(batch.nextCursor, acc :+ batch)

    loop(None, Nil)

private object MongoSessionMigrationReaderSpec:

  final case class Config(uri: String)

  def config: Option[Config] =
    sys.env.get("SEARCHESS_MONGO_URI").map(Config(_))
