package chess.server.assembly

import chess.server.config.{
  AiConfig,
  AiProviderMode,
  AppConfig,
  CorsConfig,
  EventMode,
  HistoryForwardingConfig,
  HttpConfig,
  PersistenceMode,
  PostgresConfig,
  RemoteAiConfig,
  WebSocketConfig
}
import chess.application.session.model.{SessionMode, SideController}
import org.scalatest.Assertions.cancel
import org.scalatest.BeforeAndAfterAll
import org.scalatest.EitherValues
import org.scalatest.Outcome
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

final class GameServiceRuntimePostgresContainer
    extends PostgreSQLContainer[GameServiceRuntimePostgresContainer](
      DockerImageName.parse("postgres:16-alpine")
    )

class PersistenceAssemblyPostgresRuntimeSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with BeforeAndAfterAll:

  private val postgres = GameServiceRuntimePostgresContainer()
  private var started = false

  override protected def withFixture(test: NoArgTest): Outcome =
    if !DockerClientFactory.instance().isDockerAvailable then
      cancel("Docker/Testcontainers unavailable; skipping Postgres runtime assembly integration tests")
    startContainer()
    super.withFixture(test)

  private def startContainer(): Unit =
    if !started then
      postgres.start()
      started = true

  override protected def afterAll(): Unit =
    if started then
      postgres.stop()
      started = false
    super.afterAll()

  "PersistenceAssembly" should "wire Postgres runtime persistence through Flyway-created schema" in {
    val persistence = PersistenceAssembly.assemble(config(schema = None))
    try
      val ctx =
        CoreAssembly.build(
          persistence,
          CoreEventBindings(CoreAssembly.SilentEventPublisher)
        )

      val (state, session) =
        ctx.gameService
          .createGame(
            mode = SessionMode.HumanVsHuman,
            whiteController = SideController.HumanLocal,
            blackController = SideController.HumanLocal
          )
          .value

      val loadedSession = persistence.sessionRepository.load(session.sessionId).value
      loadedSession.sessionId shouldBe session.sessionId
      loadedSession.gameId shouldBe session.gameId
      loadedSession.mode shouldBe session.mode
      loadedSession.lifecycle shouldBe session.lifecycle
      persistence.gameRepository.load(session.gameId).value shouldBe state
    finally persistence.shutdown()
  }

  it should "wire Postgres runtime persistence through a configured schema when public is non-empty" in {
    createPublicLegacyTable()

    val persistence = PersistenceAssembly.assemble(config(schema = Some("game")))
    try
      val ctx =
        CoreAssembly.build(
          persistence,
          CoreEventBindings(CoreAssembly.SilentEventPublisher)
        )

      val (state, session) =
        ctx.gameService
          .createGame(
            mode = SessionMode.HumanVsHuman,
            whiteController = SideController.HumanLocal,
            blackController = SideController.HumanLocal
          )
          .value

      persistence.sessionRepository.load(session.sessionId).value.gameId shouldBe session.gameId
      persistence.gameRepository.load(session.gameId).value shouldBe state
      tableNames("game") should contain allOf ("flyway_schema_history", "sessions", "game_states")
      tableNames("public") should contain("legacy_public_table")
      tableNames("public") should not contain "flyway_schema_history"
    finally persistence.shutdown()
  }

  private def config(schema: Option[String]): AppConfig =
    AppConfig(
      http = HttpConfig("127.0.0.1", 0),
      webSocket = WebSocketConfig(enabled = false, port = 0),
      persistence = PersistenceMode.Postgres,
      sqlite = None,
      postgres = Some(
        PostgresConfig(
          url = postgres.getJdbcUrl,
          user = postgres.getUsername,
          password = postgres.getPassword,
          schema = schema
        )
      ),
      mongo = None,
      eventMode = EventMode.InProcess,
      cors = CorsConfig(enabled = false, allowedOrigin = "*"),
      history = HistoryForwardingConfig(enabled = false, baseUrl = None, timeoutMillis = 2000),
      ai = AiConfig(
        mode = AiProviderMode.Remote,
        remote = Some(RemoteAiConfig("http://ai-service:8765")),
        timeoutMillis = 2000,
        defaultEngineId = None
      )
    )

  private def createPublicLegacyTable(): Unit =
    val db = database()
    try
      Await.result(
        db.run(
          DBIO.seq(
            sqlu"""drop table if exists public.flyway_schema_history, public.game_states, public.sessions cascade""",
            sqlu"""
              create table if not exists public.legacy_public_table (
                id integer primary key
              )
            """
          )
        ),
        10.seconds
      )
    finally db.close()

  private def tableNames(schema: String): Set[String] =
    val db = database()
    try
      Await.result(
        db.run(
          sql"""
            select table_name
            from information_schema.tables
            where table_schema = $schema
            order by table_name
          """.as[String]
        ),
        10.seconds
      ).toSet
    finally db.close()

  private def database() =
    Database.forURL(
      url = postgres.getJdbcUrl,
      user = postgres.getUsername,
      password = postgres.getPassword,
      driver = "org.postgresql.Driver"
    )
