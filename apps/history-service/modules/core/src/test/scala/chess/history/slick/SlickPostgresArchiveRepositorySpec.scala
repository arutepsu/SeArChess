package chess.history.slick

import chess.application.query.game.GameClosure
import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.Color
import chess.history.ArchiveRecord
import chess.history.postgres.HistoryFlywaySchemaInitializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Assertions.cancel
import org.scalatest.EitherValues.*
import org.scalatest.OptionValues.*
import org.scalatest.Outcome
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import slick.jdbc.PostgresProfile.api.Database
import java.time.Instant
import java.sql.DriverManager
import java.util.UUID

class SlickPostgresArchiveRepositorySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val HistorySchema = "history"
  private val container = new PostgreSQLContainer("postgres:16")
  private var db: Database                            = scala.compiletime.uninitialized
  private var repo: SlickPostgresArchiveRepository    = scala.compiletime.uninitialized
  private var started: Boolean                        = false

  override protected def withFixture(test: NoArgTest): Outcome =
    if !DockerClientFactory.instance().isDockerAvailable then
      cancel("Docker/Testcontainers unavailable; skipping PostgreSQL archive repository integration tests")
    startContainer()
    super.withFixture(test)

  private def startContainer(): Unit =
    if started then ()
    else
      container.start()
      started = true
      initializeRepository()

  private def initializeRepository(): Unit =
    Class.forName("org.postgresql.Driver")
    val conn = DriverManager.getConnection(container.getJdbcUrl, container.getUsername, container.getPassword)
    try
      conn.createStatement().execute("create table public.game_service_marker (id int primary key)")
    finally conn.close()
    HistoryFlywaySchemaInitializer.migrate(
      container.getJdbcUrl,
      container.getUsername,
      container.getPassword,
      Some(HistorySchema)
    )
    db = Database.forURL(
      url      = container.getJdbcUrl,
      user     = container.getUsername,
      password = container.getPassword,
      driver   = "org.postgresql.Driver"
    )
    repo = SlickPostgresArchiveRepository(db, Some(HistorySchema))

  override def afterAll(): Unit =
    if started then
      db.close()
      container.stop()
      started = false

  private def sampleRecord(gameId: GameId): ArchiveRecord = ArchiveRecord(
    gameId          = gameId,
    sessionId       = SessionId(UUID.fromString("00000000-0000-0000-0000-000000000011")),
    mode            = SessionMode.HumanVsHuman,
    whiteController = SideController.HumanLocal,
    blackController = SideController.HumanLocal,
    closure         = GameClosure.Resigned(Color.White),
    pgn             = Some("[Result \"1-0\"]\n\n1-0"),
    finalFen        = Some("8/8/8/8/8/8/8/4K3 w - - 0 1"),
    createdAt       = Instant.parse("2026-04-20T09:00:00Z"),
    closedAt        = Instant.parse("2026-04-20T09:01:00Z"),
    materializedAt  = Instant.parse("2026-04-20T09:02:00Z")
  )

  "SlickPostgresArchiveRepository" should "upsert and reload an archive record" in {
    val gameId = GameId(UUID.fromString("00000000-0000-0000-0000-000000000010"))
    val record = sampleRecord(gameId)
    repo.upsert(record).value
    val loaded = repo.findByGameId(gameId).value.value
    loaded.gameId shouldBe gameId
    loaded.sessionId shouldBe record.sessionId
    loaded.pgn shouldBe record.pgn
    loaded.finalFen shouldBe record.finalFen
    loaded.closure shouldBe GameClosure.Resigned(Color.White)
    loaded.mode shouldBe SessionMode.HumanVsHuman
  }

  it should "keep Flyway and archive tables in the configured history schema" in {
    val conn = DriverManager.getConnection(container.getJdbcUrl, container.getUsername, container.getPassword)
    try
      def exists(schema: String, table: String): Boolean =
        val rs = conn
          .prepareStatement(
            "select exists (select 1 from information_schema.tables where table_schema = ? and table_name = ?)"
          )
        try
          rs.setString(1, schema)
          rs.setString(2, table)
          val result = rs.executeQuery()
          result.next()
          result.getBoolean(1)
        finally rs.close()

      exists(HistorySchema, "flyway_schema_history") shouldBe true
      exists(HistorySchema, "history_archives") shouldBe true
      exists("public", "history_archives") shouldBe false
    finally conn.close()
  }

  it should "return None for an unknown gameId" in {
    val unknown = GameId(UUID.fromString("00000000-0000-0000-0000-000000000099"))
    repo.findByGameId(unknown).value shouldBe None
  }

  it should "upsert idempotently replacing the previous record" in {
    val gameId = GameId(UUID.fromString("00000000-0000-0000-0000-000000000020"))
    val r1     = sampleRecord(gameId)
    val r2     = r1.copy(pgn = Some("[Result \"0-1\"]\n\n1. e4 0-1"), closure = GameClosure.Resigned(Color.Black))
    repo.upsert(r1).value
    repo.upsert(r2).value
    val loaded = repo.findByGameId(gameId).value.value
    loaded.pgn shouldBe r2.pgn
    loaded.closure shouldBe GameClosure.Resigned(Color.Black)
  }

  it should "round-trip a record with None pgn and None finalFen (cancelled session)" in {
    val gameId = GameId(UUID.fromString("00000000-0000-0000-0000-000000000030"))
    val record = sampleRecord(gameId).copy(pgn = None, finalFen = None, closure = GameClosure.Cancelled)
    repo.upsert(record).value
    val loaded = repo.findByGameId(gameId).value.value
    loaded.pgn shouldBe None
    loaded.finalFen shouldBe None
    loaded.closure shouldBe GameClosure.Cancelled
  }
