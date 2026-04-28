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
import org.scalatest.BeforeAndAfterAll
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

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

  override protected def beforeAll(): Unit =
    super.beforeAll()
    postgres.start()

  override protected def afterAll(): Unit =
    postgres.stop()
    super.afterAll()

  "PersistenceAssembly" should "wire Postgres runtime persistence through Flyway-created schema" in {
    val persistence = PersistenceAssembly.assemble(config)
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

  private def config: AppConfig =
    AppConfig(
      http = HttpConfig("127.0.0.1", 0),
      webSocket = WebSocketConfig(enabled = false, port = 0),
      persistence = PersistenceMode.Postgres,
      sqlite = None,
      postgres = Some(
        PostgresConfig(
          url = postgres.getJdbcUrl,
          user = postgres.getUsername,
          password = postgres.getPassword
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
