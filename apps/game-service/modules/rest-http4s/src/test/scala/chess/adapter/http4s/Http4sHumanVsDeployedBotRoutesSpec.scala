package chess.adapter.http4s

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.http4s.route.{
  AuthenticatedExternalAccountLink,
  AuthenticatedSearchessUser,
  AuthenticatedUserClient,
  AuthenticatedUserClientError,
  Http4sSessionRoutes
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

class Http4sHumanVsDeployedBotRoutesSpec extends AnyFlatSpec with Matchers:

  private val validUserId = UUID.randomUUID()

  private val lichessLink = AuthenticatedExternalAccountLink(
    provider = "Lichess",
    externalId = Some("abc123"),
    externalUsername = "testplayer",
    verified = true,
    verificationSource = "oauth"
  )

  private val userWithEverything = AuthenticatedSearchessUser(
    userId = validUserId,
    nickname = Some("testplayer"),
    onboardingRequired = false,
    links = List(lichessLink)
  )

  private val userOnboardingRequired = AuthenticatedSearchessUser(
    userId = validUserId,
    nickname = None,
    onboardingRequired = true,
    links = List(lichessLink)
  )

  private val userNoNickname = AuthenticatedSearchessUser(
    userId = validUserId,
    nickname = None,
    onboardingRequired = false,
    links = List(lichessLink)
  )

  private val userNoLichessLink = AuthenticatedSearchessUser(
    userId = validUserId,
    nickname = Some("testplayer"),
    onboardingRequired = false,
    links = Nil
  )

  private val userUnverifiedLichessLink = AuthenticatedSearchessUser(
    userId = validUserId,
    nickname = Some("testplayer"),
    onboardingRequired = false,
    links = List(lichessLink.copy(verified = false))
  )

  private def stubClient(result: Either[AuthenticatedUserClientError, AuthenticatedSearchessUser]): AuthenticatedUserClient =
    (_: String) => result

  private def makeRoutes(client: AuthenticatedUserClient): HttpApp[IO] =
    val sessionRepo = InMemorySessionRepository()
    val gameRepo = InMemoryGameRepository()
    val store = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionLifecycleService = SessionLifecycleService(sessionRepo, _ => ())
    val persistentSessionService =
      PersistentSessionService(sessionRepo, gameRepo, store, sessionLifecycleService)
    val snapshotTransferService =
      SessionSnapshotTransferService(persistentSessionService, store)
    val svc = SessionGameCommandService(sessionLifecycleService, store, _ => ())
    val gameService = DefaultGameService(svc, sessionLifecycleService, gameRepo, _ => ())
    Http4sSessionRoutes(
      gameService,
      persistentSessionService,
      snapshotTransferService,
      userClient = Some(client)
    ).routes.orNotFound

  private def run(routes: HttpApp[IO], req: Request[IO]): Response[IO] =
    routes.run(req).unsafeRunSync()

  private def bodyJson(resp: Response[IO]): ujson.Value =
    ujson.read(resp.bodyText.compile.string.unsafeRunSync())

  private def postRequest(authHeader: Option[String] = Some("Bearer token")): Request[IO] =
    val base = Request[IO](Method.POST, uri"/sessions/human-vs-deployed-bot")
    authHeader match
      case Some(h) => base.withHeaders(Header.Raw(CIString("Authorization"), h))
      case None    => base

  "POST /sessions/human-vs-deployed-bot" should "return 401 when Authorization header is missing" in {
    val routes = makeRoutes(stubClient(Right(userWithEverything)))
    val resp = run(routes, postRequest(authHeader = None))
    resp.status shouldBe Status.Unauthorized
    bodyJson(resp)("code").str shouldBe "UNAUTHORIZED"
  }

  it should "return 401 when user-service rejects the token" in {
    val routes = makeRoutes(stubClient(Left(AuthenticatedUserClientError.Unauthorized("bad token"))))
    val resp = run(routes, postRequest())
    resp.status shouldBe Status.Unauthorized
    bodyJson(resp)("code").str shouldBe "UNAUTHORIZED"
  }

  it should "return 403 when onboardingRequired is true" in {
    val routes = makeRoutes(stubClient(Right(userOnboardingRequired)))
    val resp = run(routes, postRequest())
    resp.status shouldBe Status.Forbidden
    bodyJson(resp)("code").str shouldBe "ONBOARDING_REQUIRED"
  }

  it should "return 403 when nickname is missing (onboarding not flagged but nickname absent)" in {
    val routes = makeRoutes(stubClient(Right(userNoNickname)))
    val resp = run(routes, postRequest())
    resp.status shouldBe Status.Forbidden
    bodyJson(resp)("code").str shouldBe "ONBOARDING_REQUIRED"
  }

  it should "return 403 when user has no linked Lichess account" in {
    val routes = makeRoutes(stubClient(Right(userNoLichessLink)))
    val resp = run(routes, postRequest())
    resp.status shouldBe Status.Forbidden
    bodyJson(resp)("code").str shouldBe "LICHESS_LINK_REQUIRED"
  }

  it should "return 403 when user has an unverified Lichess link" in {
    val routes = makeRoutes(stubClient(Right(userUnverifiedLichessLink)))
    val resp = run(routes, postRequest())
    resp.status shouldBe Status.Forbidden
    bodyJson(resp)("code").str shouldBe "LICHESS_LINK_REQUIRED"
  }

  it should "create a session when user has verified Lichess link" in {
    val routes = makeRoutes(stubClient(Right(userWithEverything)))
    val resp = run(routes, postRequest())
    resp.status shouldBe Status.Created
    val json = bodyJson(resp)
    json("session")("mode").str shouldBe "HumanVsDeployedBot"
    json("session")("whiteController").str shouldBe "HumanLocal"
    json("session")("blackController").str shouldBe "DeployedBot"
  }

  it should "store ownerUserId and ownerNicknameSnapshot on the created session" in {
    val routes = makeRoutes(stubClient(Right(userWithEverything)))
    val resp = run(routes, postRequest())
    resp.status shouldBe Status.Created
    val json = bodyJson(resp)
    json("session")("ownerUserId").str shouldBe validUserId.toString
    json("session")("ownerNicknameSnapshot").str shouldBe "testplayer"
  }
