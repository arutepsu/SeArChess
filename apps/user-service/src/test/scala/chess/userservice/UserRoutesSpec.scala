package chess.userservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.userservice.application.{
  ExternalAccountLinkRepository,
  LichessOAuthConfig,
  LichessOAuthService,
  LichessTokenCipher,
  OAuthLinkStateRepository,
  UserProfileRepository,
  UserProfileService
}
import chess.userservice.domain.{ExternalAccountLink, OAuthLinkState, UserProfile}
import fs2.Stream
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.{Base64, UUID}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

class UserRoutesSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val testLichessConfig = LichessOAuthConfig(
    clientId         = "test-client",
    authorizeUrl     = "https://lichess.org/oauth",
    tokenUrl         = "https://lichess.org/api/token",
    accountUrl       = "https://lichess.org/api/account",
    redirectUri      = "http://localhost:10000/api/users/me/links/lichess/callback",
    stateTtlSeconds  = 600L,
    webUiSettingsUrl = "http://localhost:10000/settings"
  )

  private val noopHttpClient: Client[IO] =
    Client.fromHttpApp(HttpRoutes.of[IO] { case _ =>
      IO.pure(Response[IO](status = Status.InternalServerError))
    }.orNotFound)

  private def makeRoutes(cipher: Option[LichessTokenCipher] = None): (HttpApp[IO], UserProfileService) =
    val profileRepo  = InMemUserProfileRepository()
    val linkRepo     = InMemExternalAccountLinkRepository()
    val stateRepo    = InMemOAuthLinkStateRepository()
    val service      = UserProfileService(profileRepo, linkRepo)
    val oauthService = LichessOAuthService(stateRepo, linkRepo, noopHttpClient, testLichessConfig, cipher)
    val routes       = UserRoutes(service, oauthService, testLichessConfig)
    (routes.routes.orNotFound, service)

  private def makeToken(sub: String, username: String): String =
    val payload = s"""{"sub":"$sub","preferred_username":"$username"}"""
    val encoded = Base64.getUrlEncoder.withoutPadding.encodeToString(payload.getBytes("UTF-8"))
    s"header.$encoded.signature"

  private def bearerHeader(sub: String, username: String): Header.Raw =
    Header.Raw(org.typelevel.ci.CIString("Authorization"), s"Bearer ${makeToken(sub, username)}")

  "GET /health" should "return 200 without authorization" in {
    val (app, _) = makeRoutes()
    val req = Request[IO](method = Method.GET, uri = Uri.unsafeFromString("/health"))
    val res = app.run(req).unsafeRunSync()
    res.status shouldBe Status.Ok
  }

  "GET /users/me" should "create and return a profile on first request" in {
    val (app, _) = makeRoutes()
    val req = Request[IO](method = Method.GET, uri = Uri.unsafeFromString("/users/me"))
      .putHeaders(bearerHeader("sub-001", "alice"))
    val res  = app.run(req).unsafeRunSync()
    val body = res.bodyText.compile.string.unsafeRunSync()
    res.status shouldBe Status.Ok
    body should include("sub-001")
    body should include("alice")
    body should include("\"links\":[]")
  }

  it should "return 401 when Authorization header is missing" in {
    val (app, _) = makeRoutes()
    val req = Request[IO](method = Method.GET, uri = Uri.unsafeFromString("/users/me"))
    val res = app.run(req).unsafeRunSync()
    res.status shouldBe Status.Unauthorized
  }

  it should "return the same profile on subsequent requests" in {
    val (app, _) = makeRoutes()
    def req = Request[IO](method = Method.GET, uri = Uri.unsafeFromString("/users/me"))
      .putHeaders(bearerHeader("sub-002", "bob"))
    val res1 = app.run(req).unsafeRunSync()
    val res2 = app.run(req).unsafeRunSync()
    val id1 = ujson.read(res1.bodyText.compile.string.unsafeRunSync())("userId").str
    val id2 = ujson.read(res2.bodyText.compile.string.unsafeRunSync())("userId").str
    id1 shouldBe id2
  }

  "PUT /users/me/links/lichess/manual" should "create a ManualDev Lichess link" in {
    val (app, _) = makeRoutes()
    val req = Request[IO](method = Method.PUT, uri = Uri.unsafeFromString("/users/me/links/lichess/manual"))
      .putHeaders(bearerHeader("sub-003", "carol"))
      .withEntity("""{"lichessUsername":"carol_chess"}""")
      .putHeaders(Header.Raw(org.typelevel.ci.CIString("Content-Type"), "application/json"))
    val res  = app.run(req).unsafeRunSync()
    val body = ujson.read(res.bodyText.compile.string.unsafeRunSync())
    res.status               shouldBe Status.Ok
    body("provider").str     shouldBe "Lichess"
    body("externalUsername").str shouldBe "carol_chess"
    body("verified").bool    shouldBe false
    body("verificationSource").str shouldBe "ManualDev"
    body("capability").str   shouldBe "manual_dev"
  }

  it should "not expose token storage fields in the link JSON" in {
    val (app, _) = makeRoutes()
    val req = Request[IO](method = Method.PUT, uri = Uri.unsafeFromString("/users/me/links/lichess/manual"))
      .putHeaders(bearerHeader("sub-notoken", "notokenuser"))
      .withEntity("""{"lichessUsername":"notokenuser_chess"}""")
      .putHeaders(Header.Raw(org.typelevel.ci.CIString("Content-Type"), "application/json"))
    val body = ujson.read(app.run(req).unsafeRunSync().bodyText.compile.string.unsafeRunSync())
    body.obj.contains("tokenEncrypted") shouldBe false
    body.obj.contains("tokenScopes")    shouldBe false
    body.obj.contains("tokenStoredAt")  shouldBe false
    body.obj.contains("capability")     shouldBe true
  }

  it should "return 400 when lichessUsername is missing" in {
    val (app, _) = makeRoutes()
    val req = Request[IO](method = Method.PUT, uri = Uri.unsafeFromString("/users/me/links/lichess/manual"))
      .putHeaders(bearerHeader("sub-004", "dave"))
      .withEntity("""{"other":"field"}""")
      .putHeaders(Header.Raw(org.typelevel.ci.CIString("Content-Type"), "application/json"))
    val res = app.run(req).unsafeRunSync()
    res.status shouldBe Status.BadRequest
  }

  it should "return 401 when Authorization header is missing" in {
    val (app, _) = makeRoutes()
    val req = Request[IO](method = Method.PUT, uri = Uri.unsafeFromString("/users/me/links/lichess/manual"))
      .withEntity("""{"lichessUsername":"nobody"}""")
    val res = app.run(req).unsafeRunSync()
    res.status shouldBe Status.Unauthorized
  }

  "DELETE /users/me/links/lichess" should "remove the Lichess link and return 204" in {
    val (app, svc) = makeRoutes()
    val putReq = Request[IO](method = Method.PUT, uri = Uri.unsafeFromString("/users/me/links/lichess/manual"))
      .putHeaders(bearerHeader("sub-005", "eve"))
      .withEntity("""{"lichessUsername":"eve_chess"}""")
      .putHeaders(Header.Raw(org.typelevel.ci.CIString("Content-Type"), "application/json"))
    app.run(putReq).unsafeRunSync()

    val delReq = Request[IO](method = Method.DELETE, uri = Uri.unsafeFromString("/users/me/links/lichess"))
      .putHeaders(bearerHeader("sub-005", "eve"))
    val res = app.run(delReq).unsafeRunSync()
    res.status shouldBe Status.NoContent
  }

  "GET /users/me" should "include onboardingRequired=true when nickname is absent" in {
    val (app, _) = makeRoutes()
    val req = Request[IO](method = Method.GET, uri = Uri.unsafeFromString("/users/me"))
      .putHeaders(bearerHeader("sub-onboard", "onboarder"))
    val body = ujson.read(app.run(req).unsafeRunSync().bodyText.compile.string.unsafeRunSync())
    body("onboardingRequired").bool shouldBe true
    body("nickname").isNull         shouldBe true
  }

  "PATCH /users/me/profile" should "set nickname and return onboardingRequired=false" in {
    val (app, _) = makeRoutes()
    val req = Request[IO](method = Method.PATCH, uri = Uri.unsafeFromString("/users/me/profile"))
      .putHeaders(bearerHeader("sub-patch", "patcher"))
      .withEntity("""{"nickname":"CoolKnight"}""")
      .putHeaders(Header.Raw(org.typelevel.ci.CIString("Content-Type"), "application/json"))
    val body = ujson.read(app.run(req).unsafeRunSync().bodyText.compile.string.unsafeRunSync())
    body("nickname").str             shouldBe "CoolKnight"
    body("onboardingRequired").bool  shouldBe false
  }

  it should "return 401 when Authorization header is missing" in {
    val (app, _) = makeRoutes()
    val req = Request[IO](method = Method.PATCH, uri = Uri.unsafeFromString("/users/me/profile"))
      .withEntity("""{"nickname":"Ghost"}""")
    app.run(req).unsafeRunSync().status shouldBe Status.Unauthorized
  }

  it should "return 422 when nickname is too short" in {
    val (app, _) = makeRoutes()
    val req = Request[IO](method = Method.PATCH, uri = Uri.unsafeFromString("/users/me/profile"))
      .putHeaders(bearerHeader("sub-short", "shortie"))
      .withEntity("""{"nickname":"ab"}""")
      .putHeaders(Header.Raw(org.typelevel.ci.CIString("Content-Type"), "application/json"))
    app.run(req).unsafeRunSync().status shouldBe Status.UnprocessableEntity
  }

  it should "return 409 when nickname is already taken" in {
    val (app, _) = makeRoutes()
    def patch(sub: String, nick: String) =
      Request[IO](method = Method.PATCH, uri = Uri.unsafeFromString("/users/me/profile"))
        .putHeaders(bearerHeader(sub, sub))
        .withEntity(s"""{"nickname":"$nick"}""")
        .putHeaders(Header.Raw(org.typelevel.ci.CIString("Content-Type"), "application/json"))
    app.run(patch("sub-first", "UniqueNick")).unsafeRunSync().status shouldBe Status.Ok
    app.run(patch("sub-second", "UniqueNick")).unsafeRunSync().status shouldBe Status.Conflict
  }

  "GET /users/me/links/lichess/start" should "return 401 without JWT" in {
    val (app, _) = makeRoutes()
    val req = Request[IO](method = Method.GET, uri = Uri.unsafeFromString("/users/me/links/lichess/start"))
    val res = app.run(req).unsafeRunSync()
    res.status shouldBe Status.Unauthorized
  }

  it should "return 200 with authorizationUrl when OAuth is configured" in {
    val (app, _) = makeRoutes()
    val req = Request[IO](method = Method.GET, uri = Uri.unsafeFromString("/users/me/links/lichess/start"))
      .putHeaders(bearerHeader("sub-006", "frank"))
    val res  = app.run(req).unsafeRunSync()
    val body = ujson.read(res.bodyText.compile.string.unsafeRunSync())
    res.status shouldBe Status.Ok
    body("authorizationUrl").str should include("lichess.org/oauth")
    body("authorizationUrl").str should include("code_challenge_method=S256")
  }

  "GET /users/me/links/lichess/callback" should "redirect to settings?lichess=failed for unknown state" in {
    val (app, _) = makeRoutes()
    val req = Request[IO](
      method = Method.GET,
      uri    = Uri.unsafeFromString("/users/me/links/lichess/callback?code=c&state=unknown")
    )
    val res = app.run(req).unsafeRunSync()
    res.status shouldBe Status.Found
    res.headers.get(org.typelevel.ci.CIString("Location")).map(_.head.value) shouldBe
      Some("http://localhost:10000/settings?lichess=failed")
  }

  it should "redirect to settings?lichess=failed when code or state is missing" in {
    val (app, _) = makeRoutes()
    val req = Request[IO](method = Method.GET, uri = Uri.unsafeFromString("/users/me/links/lichess/callback"))
    val res = app.run(req).unsafeRunSync()
    res.status shouldBe Status.Found
    res.headers.get(org.typelevel.ci.CIString("Location")).map(_.head.value) shouldBe
      Some("http://localhost:10000/settings?lichess=failed")
  }

  "POST /users/me/links/lichess/upgrade" should "return 401 without JWT" in {
    val (app, _) = makeRoutes()
    val req = Request[IO](method = Method.POST, uri = Uri.unsafeFromString("/users/me/links/lichess/upgrade"))
      .withEntity("""{"targetCapability":"challenge_ready"}""")
    val res = app.run(req).unsafeRunSync()
    res.status shouldBe Status.Unauthorized
  }

  it should "return 200 with authorizationUrl for challenge_ready" in {
    val (app, _) = makeRoutes()
    val req = Request[IO](method = Method.POST, uri = Uri.unsafeFromString("/users/me/links/lichess/upgrade"))
      .putHeaders(bearerHeader("sub-upg", "upgrader"))
      .withEntity("""{"targetCapability":"challenge_ready"}""")
      .putHeaders(Header.Raw(org.typelevel.ci.CIString("Content-Type"), "application/json"))
    val res  = app.run(req).unsafeRunSync()
    val body = ujson.read(res.bodyText.compile.string.unsafeRunSync())
    res.status shouldBe Status.Ok
    body("authorizationUrl").str should include("lichess.org/oauth")
    body("authorizationUrl").str should include("code_challenge_method=S256")
    body("authorizationUrl").str should include("challenge")
  }

  it should "return 400 for an unsupported targetCapability" in {
    val (app, _) = makeRoutes()
    val req = Request[IO](method = Method.POST, uri = Uri.unsafeFromString("/users/me/links/lichess/upgrade"))
      .putHeaders(bearerHeader("sub-upg2", "upgrader2"))
      .withEntity("""{"targetCapability":"board_play"}""")
      .putHeaders(Header.Raw(org.typelevel.ci.CIString("Content-Type"), "application/json"))
    val res = app.run(req).unsafeRunSync()
    res.status shouldBe Status.BadRequest
  }

  it should "return 400 when targetCapability field is missing" in {
    val (app, _) = makeRoutes()
    val req = Request[IO](method = Method.POST, uri = Uri.unsafeFromString("/users/me/links/lichess/upgrade"))
      .putHeaders(bearerHeader("sub-upg3", "upgrader3"))
      .withEntity("""{"wrong":"field"}""")
      .putHeaders(Header.Raw(org.typelevel.ci.CIString("Content-Type"), "application/json"))
    val res = app.run(req).unsafeRunSync()
    res.status shouldBe Status.BadRequest
  }

  // ── In-memory stubs (thread-safe, per-test instance) ──────────────────────

  private class InMemUserProfileRepository extends UserProfileRepository:
    private val store = new ConcurrentHashMap[String, UserProfile]()

    override def findBySubject(sub: String): Either[String, Option[UserProfile]] =
      Right(Option(store.get(sub)))

    override def insert(profile: UserProfile): Either[String, Unit] =
      store.put(profile.keycloakSubject, profile)
      Right(())

    override def updateDisplayNameAndEmail(userId: UUID, displayName: String, email: Option[String]): Either[String, Unit] =
      store.values.asScala.find(_.userId == userId) match
        case None    => Left("Profile not found")
        case Some(p) =>
          store.put(p.keycloakSubject, p.copy(displayName = displayName, email = email))
          Right(())

    override def findByNicknameCi(nickname: String): Either[String, Option[UserProfile]] =
      Right(store.values.asScala.find(_.nickname.exists(_.equalsIgnoreCase(nickname))))

    override def setNickname(userId: UUID, nickname: String): Either[String, Unit] =
      store.values.asScala.find(_.userId == userId) match
        case None    => Left("Profile not found")
        case Some(p) =>
          store.put(p.keycloakSubject, p.copy(nickname = Some(nickname)))
          Right(())

  private class InMemExternalAccountLinkRepository extends ExternalAccountLinkRepository:
    private val store = new ConcurrentHashMap[String, ExternalAccountLink]()

    private def key(userId: UUID, provider: String) = s"$userId:$provider"

    override def findAllByUserId(userId: UUID): Either[String, List[ExternalAccountLink]] =
      Right(store.values.asScala.filter(_.userId == userId).toList)

    override def findByUserAndProvider(userId: UUID, provider: String): Either[String, Option[ExternalAccountLink]] =
      Right(Option(store.get(key(userId, provider))))

    override def insert(link: ExternalAccountLink): Either[String, Unit] =
      store.put(key(link.userId, link.provider), link)
      Right(())

    override def update(link: ExternalAccountLink): Either[String, Unit] =
      store.put(key(link.userId, link.provider), link)
      Right(())

    override def findByLichessUsername(username: String): Either[String, Option[ExternalAccountLink]] =
      Right(store.values.asScala.find(l =>
        l.provider == "Lichess" && l.externalUsername.equalsIgnoreCase(username)
      ))

    override def delete(userId: UUID, provider: String): Either[String, Unit] =
      store.remove(key(userId, provider))
      Right(())

  private class InMemOAuthLinkStateRepository extends OAuthLinkStateRepository:
    private val store = new ConcurrentHashMap[String, OAuthLinkState]()

    override def insert(state: OAuthLinkState): Either[String, Unit] =
      store.put(state.state, state)
      Right(())

    override def findAndDelete(state: String): Either[String, Option[OAuthLinkState]] =
      Right(Option(store.remove(state)))
