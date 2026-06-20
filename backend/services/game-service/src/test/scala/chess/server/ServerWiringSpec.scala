package chess.server

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.event.CollectingEventPublisher
import chess.adapter.ai.remote.RemoteAiMoveSuggestionClient
import chess.application.ai.service.AITurnError
import chess.application.session.model.{SessionMode, SideController}
import chess.server.config.{
  AiConfig,
  AiProviderMode,
  AppConfig,
  CorsConfig,
  EventMode,
  ExternalGameBotConfig,
  HttpConfig,
  HistoryForwardingConfig,
  PersistenceMode,
  SqliteConfig,
  WebSocketConfig
}
import chess.server.assembly.EventWiring
import chess.server.assembly.{CoreAssembly, PersistenceAssembly}
import fs2.Stream
import org.http4s.*
import org.http4s.implicits.*
import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString
import java.nio.file.Files

class ServerWiringSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  private val config = AppConfig(
    http = HttpConfig("127.0.0.1", 8080),
    webSocket = WebSocketConfig(enabled = false, port = 9090),
    persistence = PersistenceMode.InMemory,
    sqlite = None,
    postgres = None,
    mongo = None,
    eventMode = EventMode.InProcess,
    cors = CorsConfig(enabled = false, allowedOrigin = "*"),
    history = HistoryForwardingConfig(enabled = false, baseUrl = None, timeoutMillis = 2000),
    ai = AiConfig(
      AiProviderMode.Remote,
      remote = Some(chess.server.config.RemoteAiConfig("http://ai-service:8765")),
      timeoutMillis = 2000,
      defaultEngineId = None
    )
  )

  "ServerWiring.withServerAi" should "configure the Game Service AI endpoint path" in {
    val persistence = PersistenceAssembly.assemble(config)
    val collector = CollectingEventPublisher()
    val events = EventWiring(collector, wsServer = None)
    val baseCtx = CoreAssembly.build(persistence, events.coreEvents)
    val serverCtx = ServerWiring.withServerAi(
      baseCtx,
      events,
      AiConfig(
        AiProviderMode.LocalDeterministic,
        remote = None,
        timeoutMillis = 2000,
        defaultEngineId = None
      )
    )

    val (_, session) = serverCtx.gameService
      .createGame(
        mode = SessionMode.HumanVsAI,
        whiteController = SideController.AI(),
        blackController = SideController.HumanLocal
      )
      .value

    serverCtx.gameService.triggerAIMoveByGameId(session.gameId).isRight shouldBe true
  }

  it should "leave the base Game Service context explicit about AI absence" in {
    val persistence = PersistenceAssembly.assemble(config)
    val events = EventWiring(CollectingEventPublisher(), wsServer = None)
    val baseCtx = CoreAssembly.build(persistence, events.coreEvents)
    val (_, session) = baseCtx.gameService
      .createGame(
        mode = SessionMode.HumanVsAI,
        whiteController = SideController.AI(),
        blackController = SideController.HumanLocal
      )
      .value

    baseCtx.gameService
      .triggerAIMoveByGameId(session.gameId)
      .left
      .value shouldBe AITurnError.NotConfigured
  }

  it should "leave AI unconfigured when server AI mode is disabled" in {
    val persistence = PersistenceAssembly.assemble(config)
    val events = EventWiring(CollectingEventPublisher(), wsServer = None)
    val baseCtx = CoreAssembly.build(persistence, events.coreEvents)
    val serverCtx = ServerWiring.withServerAi(
      baseCtx,
      events,
      AiConfig(AiProviderMode.Disabled, remote = None, timeoutMillis = 2000, defaultEngineId = None)
    )
    val (_, session) = serverCtx.gameService
      .createGame(
        mode = SessionMode.HumanVsAI,
        whiteController = SideController.AI(),
        blackController = SideController.HumanLocal
      )
      .value

    serverCtx.gameService
      .triggerAIMoveByGameId(session.gameId)
      .left
      .value shouldBe AITurnError.NotConfigured
  }

  it should "select the remote AI client when remote mode is configured" in {
    val client = ServerWiring.aiClientFor(
      AiConfig(
        mode = AiProviderMode.Remote,
        remote = Some(chess.server.config.RemoteAiConfig("http://ai.local")),
        timeoutMillis = 2000,
        defaultEngineId = Some("stockfish-default")
      )
    )

    client.value shouldBe a[RemoteAiMoveSuggestionClient]
  }

  it should "select the remote AI client by default" in {
    val persistence = PersistenceAssembly.assemble(config)
    val events = EventWiring(CollectingEventPublisher(), wsServer = None)
    val baseCtx = CoreAssembly.build(persistence, events.coreEvents)
    val serverCtx = ServerWiring.withServerAi(baseCtx, events)

    val (_, session) = serverCtx.gameService
      .createGame(
        mode = SessionMode.HumanVsAI,
        whiteController = SideController.AI(),
        blackController = SideController.HumanLocal
      )
      .value

    serverCtx.gameService
      .triggerAIMoveByGameId(session.gameId)
      .left
      .value shouldBe a[AITurnError.ProviderFailure]
  }

  "ServerWiring external-game routes" should "mount when durable persistence and bot credentials are configured" in {
    withTempSqliteConfig(Some(ExternalGameBotConfig("Lichess", "searchess-bot", "secret"))) { cfg =>
      val (ctx, events) = GameServiceComposition.assemble(cfg)
      try
        ctx.externalGameService should not be empty
        val app = ServerWiring.buildPublicGameplayApi(ctx, cfg)
        val resp = app.run(
          jsonRequest(
            Method.POST,
            uri"/external-games",
            """{"platform":"lichess","externalGameId":"runtime-game-1","mode":"AiVsExternal","ourColor":"White","opponentActorId":"opponent"}""",
            Some("secret")
          )
        ).unsafeRunSync()

        resp.status shouldBe Status.Ok
        val json = bodyJson(resp)
        json("externalGameId").str shouldBe "runtime-game-1"
        json("ourActorId").str shouldBe "searchess-bot"
      finally
        events.shutdown()
        ctx.shutdownPersistence()
    }
  }

  it should "not mount external-game routes when bot credentials are absent" in {
    withTempSqliteConfig(None) { cfg =>
      val (ctx, events) = GameServiceComposition.assemble(cfg)
      try
        ctx.externalGameService should not be empty
        val app = ServerWiring.buildPublicGameplayApi(ctx, cfg)
        val resp = app.run(
          jsonRequest(
            Method.POST,
            uri"/external-games",
            """{"platform":"lichess","externalGameId":"runtime-game-2","mode":"AiVsExternal","ourColor":"White","opponentActorId":"opponent"}""",
            Some("secret")
          )
        ).unsafeRunSync()

        resp.status shouldBe Status.NotFound
      finally
        events.shutdown()
        ctx.shutdownPersistence()
    }
  }

  it should "not assemble an external-game service for in-memory production persistence" in {
    val cfg = config.copy(
      externalGameBot = Some(ExternalGameBotConfig("Lichess", "searchess-bot", "secret"))
    )
    val (ctx, events) = GameServiceComposition.assemble(cfg)
    try
      ctx.externalGameService shouldBe None
      val app = ServerWiring.buildPublicGameplayApi(ctx, cfg)
      val resp = app.run(
        jsonRequest(
          Method.POST,
          uri"/external-games",
          """{"platform":"lichess","externalGameId":"runtime-game-3","mode":"AiVsExternal","ourColor":"White","opponentActorId":"opponent"}""",
          Some("secret")
        )
      ).unsafeRunSync()

      resp.status shouldBe Status.NotFound
    finally events.shutdown()
  }

  it should "not expose configured bot API keys in invalid credential responses" in {
    withTempSqliteConfig(Some(ExternalGameBotConfig("Lichess", "searchess-bot", "super-secret"))) { cfg =>
      val (ctx, events) = GameServiceComposition.assemble(cfg)
      try
        val app = ServerWiring.buildPublicGameplayApi(ctx, cfg)
        val resp = app.run(
          jsonRequest(
            Method.POST,
            uri"/external-games",
            """{"platform":"lichess","externalGameId":"runtime-game-4","mode":"AiVsExternal","ourColor":"White","opponentActorId":"opponent"}""",
            Some("wrong")
          )
        ).unsafeRunSync()

        resp.status shouldBe Status.Unauthorized
        val body = resp.bodyText.compile.string.unsafeRunSync()
        body should not include "super-secret"
      finally
        events.shutdown()
        ctx.shutdownPersistence()
    }
  }

  private def withTempSqliteConfig[A](
      bot: Option[ExternalGameBotConfig]
  )(run: AppConfig => A): A =
    val path = Files.createTempFile("searchess-external-game-runtime", ".db")
    path.toFile.deleteOnExit()
    run(
      config.copy(
        persistence = PersistenceMode.SQLite,
        sqlite = Some(SqliteConfig(path.toString)),
        postgres = None,
        mongo = None,
        ai = AiConfig(AiProviderMode.Disabled, remote = None, timeoutMillis = 2000, defaultEngineId = None),
        externalGameBot = bot
      )
    )

  private def jsonRequest(method: Method, uri: Uri, body: String, key: Option[String]): Request[IO] =
    val base = Request[IO](method, uri)
      .withBodyStream(Stream.emits(body.getBytes("UTF-8")).covary[IO])
    key match
      case Some(value) => base.withHeaders(Header.Raw(CIString("X-Bot-Api-Key"), value))
      case None        => base

  private def bodyJson(resp: Response[IO]): ujson.Value =
    ujson.read(resp.bodyText.compile.string.unsafeRunSync())
