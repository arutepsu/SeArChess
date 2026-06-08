package chess.adapter.http4s

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.http4s.route.{
  AuthenticatedExternalAccountLink,
  AuthenticatedSearchessUser,
  AuthenticatedUserClient,
  AuthenticatedUserClientError,
  Http4sSessionRoutes,
  InMemoryBotSessionRateLimiter
}
import chess.adapter.repository.{
  InMemoryGameRepository,
  InMemorySessionGameStore,
  InMemorySessionRepository
}
import chess.application.DefaultGameService
import chess.application.session.service.{
  PersistentSessionService,
  SessionGameCommandService,
  SessionLifecycleService,
  SessionSnapshotTransferService
}
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString
import java.util.UUID

class Http4sHumanVsDeployedBotRateLimitSpec extends AnyFlatSpec with Matchers:

  private val lichessLink = AuthenticatedExternalAccountLink(
    provider = "Lichess",
    externalId = Some("abc"),
    externalUsername = "player",
    verified = true,
    verificationSource = "oauth"
  )

  private def eligibleUser(id: UUID) = AuthenticatedSearchessUser(
    userId = id,
    nickname = Some("player"),
    onboardingRequired = false,
    links = List(lichessLink)
  )

  private def stubClient(userId: UUID): AuthenticatedUserClient =
    (_: String) => Right(eligibleUser(userId))

  private def makeRoutes(userId: UUID, limitPerWindow: Int, windowSeconds: Int = 60): HttpApp[IO] =
    val sessionRepo = InMemorySessionRepository()
    val gameRepo = InMemoryGameRepository()
    val store = InMemorySessionGameStore(sessionRepo, gameRepo)
    val lifecycleService = SessionLifecycleService(sessionRepo, _ => ())
    val persistentService = PersistentSessionService(sessionRepo, gameRepo, store, lifecycleService)
    val snapshotService = SessionSnapshotTransferService(persistentService, store)
    val svc = SessionGameCommandService(lifecycleService, store, _ => ())
    val gameService = DefaultGameService(svc, lifecycleService, gameRepo, _ => ())
    val limiter = InMemoryBotSessionRateLimiter(limitPerWindow, windowSeconds)
    Http4sSessionRoutes(
      gameService,
      persistentService,
      snapshotService,
      userClient = Some(stubClient(userId)),
      botRateLimiter = Some(limiter)
    ).routes.orNotFound

  private def run(routes: HttpApp[IO], req: Request[IO]): Response[IO] =
    routes.run(req).unsafeRunSync()

  private def bodyJson(resp: Response[IO]): ujson.Value =
    ujson.read(resp.bodyText.compile.string.unsafeRunSync())

  private def postRequest(): Request[IO] =
    Request[IO](Method.POST, uri"/sessions/human-vs-deployed-bot")
      .withHeaders(Header.Raw(CIString("Authorization"), "Bearer token"))

  "BotSession rate limiter" should "allow requests under the limit" in {
    val userId = UUID.randomUUID()
    val routes = makeRoutes(userId, limitPerWindow = 3)

    for _ <- 1 to 3 do
      val resp = run(routes, postRequest())
      resp.status shouldBe Status.Created
  }

  it should "reject the request that exceeds the per-user limit with 429" in {
    val userId = UUID.randomUUID()
    val routes = makeRoutes(userId, limitPerWindow = 2)

    run(routes, postRequest()).status shouldBe Status.Created
    run(routes, postRequest()).status shouldBe Status.Created
    val limited = run(routes, postRequest())
    limited.status shouldBe Status.TooManyRequests
    bodyJson(limited)("code").str shouldBe "BOT_GAME_RATE_LIMITED"
  }

  it should "key the limit by userId — one user hitting the limit does not affect another" in {
    val userA = UUID.randomUUID()
    val userB = UUID.randomUUID()

    val routesA = makeRoutes(userA, limitPerWindow = 1)
    val routesB = makeRoutes(userB, limitPerWindow = 1)

    // Exhaust user A's limit
    run(routesA, postRequest()).status shouldBe Status.Created
    run(routesA, postRequest()).status shouldBe Status.TooManyRequests

    // User B's limit is independent
    run(routesB, postRequest()).status shouldBe Status.Created
  }

  it should "allow exactly limitPerWindow requests and deny the next" in {
    val userId = UUID.randomUUID()
    val limit = 5
    val routes = makeRoutes(userId, limitPerWindow = limit)

    for i <- 1 to limit do
      withClue(s"request $i of $limit should succeed: ") {
        run(routes, postRequest()).status shouldBe Status.Created
      }

    withClue(s"request ${limit + 1} should be rate-limited: ") {
      run(routes, postRequest()).status shouldBe Status.TooManyRequests
    }
  }

  it should "include BOT_GAME_RATE_LIMITED code in the 429 response body" in {
    val userId = UUID.randomUUID()
    val routes = makeRoutes(userId, limitPerWindow = 1)

    run(routes, postRequest()).status shouldBe Status.Created
    val resp = run(routes, postRequest())
    resp.status shouldBe Status.TooManyRequests
    val json = bodyJson(resp)
    json("code").str shouldBe "BOT_GAME_RATE_LIMITED"
    json("message").str should not be empty
  }
