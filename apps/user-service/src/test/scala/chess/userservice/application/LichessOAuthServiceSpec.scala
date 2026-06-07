package chess.userservice.application

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.userservice.domain.{ExternalAccountLink, OAuthLinkState}
import fs2.Stream
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID
import scala.collection.mutable

class LichessOAuthServiceSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val testConfig = LichessOAuthConfig(
    clientId         = "test-client",
    authorizeUrl     = "https://lichess.org/oauth",
    tokenUrl         = "https://lichess.org/api/token",
    accountUrl       = "https://lichess.org/api/account",
    redirectUri      = "http://localhost:10000/api/users/me/links/lichess/callback",
    stateTtlSeconds  = 600L,
    webUiSettingsUrl = "http://localhost:10000/settings"
  )

  private val unconfigured = testConfig.copy(clientId = "", redirectUri = "")

  private def noOpClient: Client[IO] =
    Client.fromHttpApp(HttpRoutes.of[IO] { case _ =>
      IO.pure(Response[IO](status = Status.InternalServerError))
    }.orNotFound)

  private def lichessMockClient(lichessId: String): Client[IO] =
    val tokenResp   = s"""{"access_token":"tok-test","token_type":"Bearer","expires_in":3600}"""
    val accountResp = s"""{"id":"$lichessId","username":"$lichessId"}"""
    Client.fromHttpApp(HttpRoutes.of[IO] {
      case req if req.method == Method.POST =>
        IO.pure(Response[IO](
          status  = Status.Ok,
          headers = Headers(`Content-Type`(MediaType.application.json)),
          body    = Stream.emits(tokenResp.getBytes("UTF-8")).covary[IO]
        ))
      case req if req.method == Method.GET =>
        IO.pure(Response[IO](
          status  = Status.Ok,
          headers = Headers(`Content-Type`(MediaType.application.json)),
          body    = Stream.emits(accountResp.getBytes("UTF-8")).covary[IO]
        ))
    }.orNotFound)

  private def makeService(
      stateRepo: OAuthLinkStateRepository = StubOAuthLinkStateRepository(),
      linkRepo: ExternalAccountLinkRepository = StubExternalAccountLinkRepository(),
      client: Client[IO] = noOpClient,
      cfg: LichessOAuthConfig = testConfig
  ): LichessOAuthService =
    LichessOAuthService(stateRepo, linkRepo, client, cfg)

  "createLinkStart" should "return Left when OAuth is not configured" in {
    val svc = makeService(cfg = unconfigured)
    svc.createLinkStart(UUID.randomUUID()).isLeft shouldBe true
  }

  it should "insert a state row and return an authorization URL" in {
    val stateRepo = StubOAuthLinkStateRepository()
    val svc       = makeService(stateRepo = stateRepo)
    val userId    = UUID.randomUUID()
    val url       = svc.createLinkStart(userId).value
    url should include("https://lichess.org/oauth")
    url should include("code_challenge_method=S256")
    url should include("code_challenge=")
    url should include("state=")
    stateRepo.store should have size 1
    stateRepo.store.values.head.userId shouldBe userId
  }

  it should "not persist the code_verifier in a link row" in {
    val linkRepo = StubExternalAccountLinkRepository()
    val svc      = makeService(linkRepo = linkRepo)
    svc.createLinkStart(UUID.randomUUID())
    linkRepo.store shouldBe empty
  }

  "exchangeCallback" should "return Left for an unknown state" in {
    val svc    = makeService()
    val result = svc.exchangeCallback("code123", "nonexistent-state").unsafeRunSync()
    result.isLeft shouldBe true
    result.left.value should include("Unknown")
  }

  it should "return Left for an expired state" in {
    val stateRepo = StubOAuthLinkStateRepository()
    val userId    = UUID.randomUUID()
    val expired   = OAuthLinkState(
      state                = "expired-state",
      userId               = userId,
      codeVerifier         = "verifier",
      redirectAfterSuccess = "http://localhost/settings?lichess=linked",
      expiresAt            = Instant.now().minusSeconds(1),
      createdAt            = Instant.now().minusSeconds(700)
    )
    stateRepo.store("expired-state") = expired
    val svc    = makeService(stateRepo = stateRepo)
    val result = svc.exchangeCallback("code123", "expired-state").unsafeRunSync()
    result.isLeft shouldBe true
    result.left.value should include("expired")
  }

  it should "consume the state atomically (findAndDelete prevents reuse)" in {
    val stateRepo = StubOAuthLinkStateRepository()
    val userId    = UUID.randomUUID()
    val ls        = OAuthLinkState(
      state                = "one-time-state",
      userId               = userId,
      codeVerifier         = "verifier",
      redirectAfterSuccess = "http://localhost/settings?lichess=linked",
      expiresAt            = Instant.now().plusSeconds(600),
      createdAt            = Instant.now()
    )
    stateRepo.store("one-time-state") = ls
    val svc = makeService(stateRepo = stateRepo, client = lichessMockClient("alice"))
    svc.exchangeCallback("code", "one-time-state").unsafeRunSync()
    // second call — state is gone
    val second = svc.exchangeCallback("code", "one-time-state").unsafeRunSync()
    second.isLeft shouldBe true
    second.left.value should include("Unknown")
  }

  it should "upsert a verified link with verificationSource=OAuthPKCE and not persist the token" in {
    val stateRepo = StubOAuthLinkStateRepository()
    val linkRepo  = StubExternalAccountLinkRepository()
    val userId    = UUID.randomUUID()
    val ls        = OAuthLinkState(
      state                = "valid-state",
      userId               = userId,
      codeVerifier         = "verifier",
      redirectAfterSuccess = "http://localhost/settings?lichess=linked",
      expiresAt            = Instant.now().plusSeconds(600),
      createdAt            = Instant.now()
    )
    stateRepo.store("valid-state") = ls
    val svc    = makeService(stateRepo = stateRepo, linkRepo = linkRepo, client = lichessMockClient("alice_chess"))
    val result = svc.exchangeCallback("code", "valid-state").unsafeRunSync()

    val link = result.value
    link.provider           shouldBe "Lichess"
    link.externalUsername   shouldBe "alice_chess"
    link.verified           shouldBe true
    link.verificationSource shouldBe "OAuthPKCE"
    // Access token must not appear in any persisted field
    val serialised = link.toString
    serialised should not include "tok-test"
  }

  // ── In-memory stubs ───────────────────────────────────────────────────────

  private class StubOAuthLinkStateRepository extends OAuthLinkStateRepository:
    val store: mutable.Map[String, OAuthLinkState] = mutable.Map.empty

    override def insert(state: OAuthLinkState): Either[String, Unit] =
      store(state.state) = state
      Right(())

    override def findAndDelete(state: String): Either[String, Option[OAuthLinkState]] =
      Right(store.remove(state))

  private class StubExternalAccountLinkRepository extends ExternalAccountLinkRepository:
    val store: mutable.Map[(UUID, String), ExternalAccountLink] = mutable.Map.empty

    override def findAllByUserId(userId: UUID): Either[String, List[ExternalAccountLink]] =
      Right(store.values.filter(_.userId == userId).toList)

    override def findByUserAndProvider(userId: UUID, provider: String): Either[String, Option[ExternalAccountLink]] =
      Right(store.get((userId, provider)))

    override def insert(link: ExternalAccountLink): Either[String, Unit] =
      store((link.userId, link.provider)) = link
      Right(())

    override def update(link: ExternalAccountLink): Either[String, Unit] =
      store((link.userId, link.provider)) = link
      Right(())

    override def delete(userId: UUID, provider: String): Either[String, Unit] =
      store.remove((userId, provider))
      Right(())
