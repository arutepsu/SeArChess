package chess.server.assembly

import chess.application.session.model.{GameSession, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.domain.state.GameStateFactory
import chess.server.config.{
  AiConfig,
  AiProviderMode,
  AppConfig,
  CorsConfig,
  EventMode,
  HistoryForwardingConfig,
  HttpConfig,
  MongoConfig,
  PersistenceMode,
  RemoteAiConfig,
  WebSocketConfig
}
import chess.server.persistence.MongoPersistenceRuntime
import org.scalatest.BeforeAndAfterAll
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName

import java.time.Instant
import java.util.UUID

final class GameServiceRuntimeMongoContainer
    extends MongoDBContainer(DockerImageName.parse("mongo:7"))

class PersistenceAssemblyMongoRuntimeSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with BeforeAndAfterAll:

  private val mongo = GameServiceRuntimeMongoContainer()

  override protected def beforeAll(): Unit =
    super.beforeAll()
    mongo.start()

  override protected def afterAll(): Unit =
    mongo.stop()
    super.afterAll()

  "PersistenceAssembly" should "wire Mongo runtime persistence through initialized collections" in {
    val persistence = PersistenceAssembly.assemble(config(databaseName()))
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

      persistence.sessionRepository.load(session.sessionId).value shouldBe session
      persistence.gameRepository.load(session.gameId).value shouldBe state
    finally persistence.shutdown()
  }

  it should "load data written through the shared Mongo persistence setup" in {
    val database = databaseName()
    val mongoConfig = MongoConfig(mongo.getConnectionString, database)
    val seedRuntime = MongoPersistenceRuntime.open(mongoConfig).value
    val gameId = GameId.random()
    val session =
      GameSession.create(
        gameId = gameId,
        mode = SessionMode.HumanVsHuman,
        whiteController = SideController.HumanLocal,
        blackController = SideController.HumanLocal,
        now = Instant.parse("2026-04-28T12:00:00Z")
      )
    val state = GameStateFactory.initial()
    try seedRuntime.store.save(session, state).value
    finally seedRuntime.close()

    val persistence = PersistenceAssembly.assemble(config(database))
    try
      persistence.sessionRepository.load(session.sessionId).value shouldBe session
      persistence.gameRepository.load(gameId).value shouldBe state
    finally persistence.shutdown()
  }

  private def databaseName(): String =
    s"searchess_runtime_${UUID.randomUUID().toString.replace("-", "")}"

  private def config(database: String): AppConfig =
    AppConfig(
      http = HttpConfig("127.0.0.1", 0),
      webSocket = WebSocketConfig(enabled = false, port = 0),
      persistence = PersistenceMode.Mongo,
      sqlite = None,
      postgres = None,
      mongo = Some(MongoConfig(mongo.getConnectionString, database)),
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
