package chess.lichessbridge

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.unsafe.implicits.global
import chess.lichessbridge.LichessError.*
import org.http4s.*
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class LichessBridgeRoutesSpec extends AnyFlatSpec with Matchers:

  // ── Fixtures ─────────────────────────────────────────────────────────────────

  private val defaultConfig = LichessBridgeConfig(
    enabled            = false,
    lichessApiBaseUrl  = "https://lichess.org",
    lichessBotUsername = None,
    lichessBotToken    = None,
    aiServiceUrl       = "http://ai-service:8765",
    maxConcurrentGames = 1,
    host               = "0.0.0.0",
    port               = 8090
  )

  private val configWithToken = defaultConfig.copy(
    lichessBotToken    = Some("lip_test_token"),
    lichessBotUsername = Some("searchess-bot")
  )

  private val configEnabledWithToken = configWithToken.copy(enabled = true)

  private def emptyStateRef(): Ref[IO, WorkerState] =
    IO.ref(WorkerState.empty).unsafeRunSync()

  /** Mock LichessClient that returns a fixed profile result. */
  private def mockProfileClient(result: Either[LichessError, BotProfile]): LichessClient[IO] =
    new LichessClient[IO]:
      def getBotProfile(token: String)                                                              = IO.pure(result)
      def validateToken(token: String)                                                              = IO.pure(result.map(_ => true))
      def challengeAi(token: String, level: Int, clockLimit: Int, clockIncrement: Int)             = IO.pure(Left(NetworkError("not used")))
      def acceptChallenge(token: String, challengeId: String)                                      = IO.pure(Left(NetworkError("not used")))
      def declineChallenge(token: String, challengeId: String, reason: String)                     = IO.pure(Left(NetworkError("not used")))
      def submitMove(token: String, gameId: String, move: String)                                  = IO.pure(Left(NetworkError("not used")))

  /** Mock LichessClient that returns a fixed challenge result. */
  private def mockChallengeClient(result: Either[LichessError, ChallengeResult]): LichessClient[IO] =
    new LichessClient[IO]:
      def getBotProfile(token: String)                                                              = IO.pure(Left(NetworkError("not used")))
      def validateToken(token: String)                                                              = IO.pure(Left(NetworkError("not used")))
      def challengeAi(token: String, level: Int, clockLimit: Int, clockIncrement: Int)             = IO.pure(result)
      def acceptChallenge(token: String, challengeId: String)                                      = IO.pure(Left(NetworkError("not used")))
      def declineChallenge(token: String, challengeId: String, reason: String)                     = IO.pure(Left(NetworkError("not used")))
      def submitMove(token: String, gameId: String, move: String)                                  = IO.pure(Left(NetworkError("not used")))

  private def makeApp(
      config: LichessBridgeConfig = defaultConfig,
      client: LichessClient[IO] = StubLichessClient(),
      stateRef: Ref[IO, WorkerState] = emptyStateRef()
  ): HttpApp[IO] =
    LichessBridgeRoutes(config, client, stateRef).routes.orNotFound

  private def get(
      path: Uri,
      config: LichessBridgeConfig = defaultConfig,
      client: LichessClient[IO] = StubLichessClient(),
      stateRef: Ref[IO, WorkerState] = emptyStateRef()
  ): Response[IO] =
    makeApp(config, client, stateRef).run(Request[IO](Method.GET, path)).unsafeRunSync()

  private def post(
      path: Uri,
      config: LichessBridgeConfig = defaultConfig,
      client: LichessClient[IO] = StubLichessClient(),
      stateRef: Ref[IO, WorkerState] = emptyStateRef()
  ): Response[IO] =
    makeApp(config, client, stateRef).run(Request[IO](Method.POST, path)).unsafeRunSync()

  private def bodyJson(resp: Response[IO]): ujson.Value =
    ujson.read(resp.bodyText.compile.string.unsafeRunSync())

  // ── /health ───────────────────────────────────────────────────────────────────

  "GET /health" should "return 200 with status ok" in {
    val resp = get(uri"/health")
    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("status").str  shouldBe "ok"
    json("service").str shouldBe "lichess-bridge-service"
  }

  // ── /internal/lichess/status ─────────────────────────────────────────────────

  "GET /internal/lichess/status" should "return 200 with enabled=false by default" in {
    val resp = get(uri"/internal/lichess/status")
    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("service").str                shouldBe "lichess-bridge-service"
    json("enabled").bool               shouldBe false
    json("botUsernameConfigured").bool shouldBe false
    json("tokenConfigured").bool       shouldBe false
    json("activeGames").arr            shouldBe empty
    json("phase").str                  shouldBe "2B-2"
  }

  it should "reflect workerRunning=false when state is empty" in {
    val resp = get(uri"/internal/lichess/status")
    bodyJson(resp)("workerRunning").bool shouldBe false
  }

  it should "reflect workerRunning=true when state says running" in {
    val stateRef = IO.ref(WorkerState.empty.started).unsafeRunSync()
    val resp     = get(uri"/internal/lichess/status", stateRef = stateRef)
    bodyJson(resp)("workerRunning").bool shouldBe true
  }

  it should "reflect botUsernameConfigured=true when username is set" in {
    val cfg  = defaultConfig.copy(lichessBotUsername = Some("searchess-bot"))
    val resp = get(uri"/internal/lichess/status", cfg)
    bodyJson(resp)("botUsernameConfigured").bool shouldBe true
  }

  it should "reflect enabled=true when bridge is enabled" in {
    val cfg  = defaultConfig.copy(enabled = true)
    val resp = get(uri"/internal/lichess/status", cfg)
    bodyJson(resp)("enabled").bool shouldBe true
  }

  it should "show tokenConfigured=true when token is set" in {
    val cfg  = defaultConfig.copy(lichessBotToken = Some("lip_test"))
    val resp = get(uri"/internal/lichess/status", cfg)
    bodyJson(resp)("tokenConfigured").bool shouldBe true
  }

  it should "never include the token value in the response body" in {
    val cfg  = defaultConfig.copy(lichessBotToken = Some("lip_secret_value"))
    val body = get(uri"/internal/lichess/status", cfg).bodyText.compile.string.unsafeRunSync()
    body should not include "lip_secret_value"
  }

  it should "include aiServiceUrl in response" in {
    val resp = get(uri"/internal/lichess/status")
    bodyJson(resp)("aiServiceUrl").str shouldBe "http://ai-service:8765"
  }

  it should "include activeGames from state" in {
    val game     = ActiveGame("g1", "human1", Some("white"), Instant.parse("2025-01-01T00:00:00Z"))
    val stateRef = IO.ref(WorkerState.empty.addGame(game)).unsafeRunSync()
    val resp     = get(uri"/internal/lichess/status", stateRef = stateRef)
    val json     = bodyJson(resp)
    json("activeGamesCount").num shouldBe 1.0
    val gameObj = json("activeGames").arr.head
    gameObj("gameId").str   shouldBe "g1"
    gameObj("opponent").str shouldBe "human1"
  }

  it should "include lastError when present" in {
    val stateRef = IO.ref(WorkerState.empty.withError("token invalid")).unsafeRunSync()
    val resp     = get(uri"/internal/lichess/status", stateRef = stateRef)
    bodyJson(resp)("lastError").str shouldBe "token invalid"
  }

  it should "never include token in active games metadata" in {
    val cfg      = defaultConfig.copy(lichessBotToken = Some("lip_secret"))
    val game     = ActiveGame("g1", "human1", None, Instant.now())
    val stateRef = IO.ref(WorkerState.empty.addGame(game)).unsafeRunSync()
    val body     = get(uri"/internal/lichess/status", cfg, stateRef = stateRef)
      .bodyText.compile.string.unsafeRunSync()
    body should not include "lip_secret"
  }

  it should "include lastSubmittedMove and lastMoveCount in active game when set" in {
    val game = ActiveGame("g1", "human1", Some("white"), Instant.parse("2025-01-01T00:00:00Z"))
      .withSubmittedMove("e2e4", 0)
    val stateRef = IO.ref(WorkerState.empty.addGame(game)).unsafeRunSync()
    val json     = bodyJson(get(uri"/internal/lichess/status", stateRef = stateRef))
    val gameObj  = json("activeGames").arr.head
    gameObj("lastSubmittedMove").str shouldBe "e2e4"
    gameObj("lastMoveCount").num     shouldBe 0.0
  }

  it should "not expose Fiber references or raw NDJSON in the status response" in {
    val game     = ActiveGame("g1", "human1", None, Instant.now())
    val stateRef = IO.ref(WorkerState.empty.addGame(game)).unsafeRunSync()
    val body     = get(uri"/internal/lichess/status", stateRef = stateRef)
      .bodyText.compile.string.unsafeRunSync()
    body should not include "Fiber"
    body should not include "ndjson"
  }

  // ── /internal/lichess/validate ───────────────────────────────────────────────

  "GET /internal/lichess/validate" should "return disabled_not_configured when bridge off and no token" in {
    val resp = get(uri"/internal/lichess/validate")
    resp.status shouldBe Status.Ok
    bodyJson(resp)("status").str shouldBe "disabled_not_configured"
  }

  it should "return not_configured when token absent (enabled=true)" in {
    val cfg  = defaultConfig.copy(enabled = true)
    val resp = get(uri"/internal/lichess/validate", cfg)
    resp.status shouldBe Status.Ok
    bodyJson(resp)("status").str shouldBe "not_configured"
  }

  it should "return ok with botProfile when mock client succeeds" in {
    val profile = BotProfile("searchess-bot", "SearchessBot", Some("BOT"), isBot = true)
    val client  = mockProfileClient(Right(profile))
    val resp    = get(uri"/internal/lichess/validate", configWithToken, client)
    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("status").str              shouldBe "ok"
    json("botProfile")("id").str    shouldBe "searchess-bot"
    json("botProfile")("isBot").bool shouldBe true
  }

  it should "return 401 when mock returns Unauthorized" in {
    val client = mockProfileClient(Left(Unauthorized("rejected")))
    val resp   = get(uri"/internal/lichess/validate", configWithToken, client)
    resp.status shouldBe Status.Unauthorized
    bodyJson(resp)("status").str shouldBe "invalid_token"
  }

  it should "return 429 when mock returns RateLimited" in {
    val client = mockProfileClient(Left(RateLimited(Some(60))))
    val resp   = get(uri"/internal/lichess/validate", configWithToken, client)
    resp.status shouldBe Status.TooManyRequests
    bodyJson(resp)("status").str shouldBe "rate_limited"
  }

  it should "return 503 when mock returns NetworkError" in {
    val client = mockProfileClient(Left(NetworkError("connection refused")))
    val resp   = get(uri"/internal/lichess/validate", configWithToken, client)
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("status").str shouldBe "network_error"
  }

  it should "return 502 when mock returns UnexpectedResponse" in {
    val client = mockProfileClient(Left(UnexpectedResponse(500, "internal error")))
    val resp   = get(uri"/internal/lichess/validate", configWithToken, client)
    resp.status shouldBe Status.BadGateway
    bodyJson(resp)("status").str shouldBe "upstream_error"
  }

  // ── /internal/lichess/challenge-ai/spike ─────────────────────────────────────

  "POST /internal/lichess/challenge-ai/spike" should "return 403 bridge_disabled when bridge is disabled" in {
    val resp = post(uri"/internal/lichess/challenge-ai/spike")
    resp.status shouldBe Status.Forbidden
    bodyJson(resp)("status").str shouldBe "bridge_disabled"
  }

  it should "return 403 no_token when bridge enabled but no token" in {
    val cfg  = defaultConfig.copy(enabled = true)
    val resp = post(uri"/internal/lichess/challenge-ai/spike", cfg)
    resp.status shouldBe Status.Forbidden
    bodyJson(resp)("status").str shouldBe "no_token"
  }

  it should "return 403 no_bot_username when enabled and token present but no username" in {
    val cfg  = defaultConfig.copy(enabled = true, lichessBotToken = Some("lip_test"))
    val resp = post(uri"/internal/lichess/challenge-ai/spike", cfg)
    resp.status shouldBe Status.Forbidden
    bodyJson(resp)("status").str shouldBe "no_bot_username"
  }

  it should "return ok with created result on mock success" in {
    val result = ChallengeResult("abc123", "https://lichess.org/abc123")
    val client = mockChallengeClient(Right(result))
    val resp   = post(uri"/internal/lichess/challenge-ai/spike", configEnabledWithToken, client)
    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("status").str  shouldBe "created"
    json("gameId").str  shouldBe "abc123"
    json("gameUrl").str shouldBe "https://lichess.org/abc123"
  }

  it should "return 401 when challenge returns Unauthorized" in {
    val client = mockChallengeClient(Left(Unauthorized("rejected")))
    val resp   = post(uri"/internal/lichess/challenge-ai/spike", configEnabledWithToken, client)
    resp.status shouldBe Status.Unauthorized
  }

  it should "return 429 when challenge returns RateLimited" in {
    val client = mockChallengeClient(Left(RateLimited(None)))
    val resp   = post(uri"/internal/lichess/challenge-ai/spike", configEnabledWithToken, client)
    resp.status shouldBe Status.TooManyRequests
  }

  it should "return 503 when challenge returns NetworkError" in {
    val client = mockChallengeClient(Left(NetworkError("timeout")))
    val resp   = post(uri"/internal/lichess/challenge-ai/spike", configEnabledWithToken, client)
    resp.status shouldBe Status.ServiceUnavailable
  }

  // ── /internal/lichess/policy ─────────────────────────────────────────────────

  "GET /internal/lichess/policy" should "return current policy config" in {
    val cfg = defaultConfig.copy(
      acceptChallenges = false,
      acceptRated      = false,
      allowedVariants  = Set("standard"),
      minClockSeconds  = 180,
      maxClockSeconds  = 600
    )
    val resp = get(uri"/internal/lichess/policy", cfg)
    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("acceptChallenges").bool         shouldBe false
    json("acceptRated").bool              shouldBe false
    json("allowedVariants").arr.map(_.str) should contain("standard")
    json("minClockSeconds").num           shouldBe 180.0
    json("maxClockSeconds").num           shouldBe 600.0
    json("maxConcurrentGames").num        shouldBe 1.0
  }

  it should "return null for allowedChallengers when list is empty (accept all)" in {
    val resp = get(uri"/internal/lichess/policy")
    bodyJson(resp)("allowedChallengers") shouldBe ujson.Null
  }

  it should "return the allowedChallengers list when configured" in {
    val cfg  = defaultConfig.copy(allowedChallengers = Set("alice", "bob"))
    val resp = get(uri"/internal/lichess/policy", cfg)
    val arr  = bodyJson(resp)("allowedChallengers").arr.map(_.str).toSet
    arr shouldBe Set("alice", "bob")
  }

  it should "never include token in policy response" in {
    val cfg  = defaultConfig.copy(lichessBotToken = Some("lip_secret"))
    val body = get(uri"/internal/lichess/policy", cfg).bodyText.compile.string.unsafeRunSync()
    body should not include "lip_secret"
  }

  // ── 404 ──────────────────────────────────────────────────────────────────────

  "GET /unknown" should "return 404" in {
    get(uri"/unknown").status shouldBe Status.NotFound
  }
