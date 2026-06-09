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
import java.util.{Base64, UUID}
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

  private def testCipher(): LichessTokenCipher =
    LichessTokenCipher.fromBase64Key(Base64.getEncoder.encodeToString(Array.fill[Byte](32)(1))).value

  private def makeService(
      stateRepo: OAuthLinkStateRepository = StubOAuthLinkStateRepository(),
      linkRepo: ExternalAccountLinkRepository = StubExternalAccountLinkRepository(),
      client: Client[IO] = noOpClient,
      cfg: LichessOAuthConfig = testConfig,
      cipher: Option[LichessTokenCipher] = None
  ): LichessOAuthService =
    LichessOAuthService(stateRepo, linkRepo, client, cfg, cipher)

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
    stateRepo.store.values.head.userId           shouldBe userId
    stateRepo.store.values.head.targetCapability shouldBe "identity_only"
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
      createdAt            = Instant.now().minusSeconds(700),
      targetCapability     = "identity_only"
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
      createdAt            = Instant.now(),
      targetCapability     = "identity_only"
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
      createdAt            = Instant.now(),
      targetCapability     = "identity_only"
    )
    stateRepo.store("valid-state") = ls
    val svc    = makeService(stateRepo = stateRepo, linkRepo = linkRepo, client = lichessMockClient("alice_chess"))
    val result = svc.exchangeCallback("code", "valid-state").unsafeRunSync()

    val link = result.value
    link.provider           shouldBe "Lichess"
    link.externalUsername   shouldBe "alice_chess"
    link.verified           shouldBe true
    link.verificationSource shouldBe "OAuthPKCE"
    link.capability         shouldBe "identity_only"
    link.tokenEncrypted     shouldBe None
    link.tokenScopes        shouldBe None
    link.tokenStoredAt      shouldBe None
    // Access token must not appear in any persisted field
    val serialised = link.toString
    serialised should not include "tok-test"
  }

  it should "fail closed for an unknown targetCapability without updating the link" in {
    val stateRepo = StubOAuthLinkStateRepository()
    val linkRepo  = StubExternalAccountLinkRepository()
    val userId    = UUID.randomUUID()
    val ls        = OAuthLinkState(
      state                = "unknown-cap-state",
      userId               = userId,
      codeVerifier         = "verifier",
      redirectAfterSuccess = "http://localhost/settings?lichess=linked",
      expiresAt            = Instant.now().plusSeconds(600),
      createdAt            = Instant.now(),
      targetCapability     = "board_play"  // unsupported capability
    )
    stateRepo.store("unknown-cap-state") = ls
    val svc    = makeService(stateRepo = stateRepo, linkRepo = linkRepo, client = lichessMockClient("alice"))
    val result = svc.exchangeCallback("code", "unknown-cap-state").unsafeRunSync()

    result.isLeft        shouldBe true
    result.left.value    shouldBe "unsupported_target_capability"
    linkRepo.store       shouldBe empty  // no link was upserted
  }

  // ── createUpgradeStart tests ──────────────────────────────────────────────

  "createUpgradeStart" should "create a state row with targetCapability=challenge_ready and use upgradeScope" in {
    val stateRepo = StubOAuthLinkStateRepository()
    val svc       = makeService(stateRepo = stateRepo)
    val userId    = UUID.randomUUID()
    val url       = svc.createUpgradeStart(userId, "challenge_ready").value
    stateRepo.store should have size 1
    stateRepo.store.values.head.userId           shouldBe userId
    stateRepo.store.values.head.targetCapability shouldBe "challenge_ready"
    url should include("https://lichess.org/oauth")
    url should include("challenge")  // upgradeScope contains "challenge:write"
    url should include("code_challenge_method=S256")
  }

  it should "return Left for an unsupported targetCapability" in {
    val stateRepo = StubOAuthLinkStateRepository()
    val svc       = makeService(stateRepo = stateRepo)
    val result    = svc.createUpgradeStart(UUID.randomUUID(), "board_play")
    result.isLeft         shouldBe true
    result.left.value     shouldBe "unsupported_target_capability"
    stateRepo.store       shouldBe empty
  }

  it should "return Left when OAuth is not configured" in {
    val svc    = makeService(cfg = unconfigured)
    val result = svc.createUpgradeStart(UUID.randomUUID(), "challenge_ready")
    result.isLeft shouldBe true
  }

  // ── challenge_ready callback tests ───────────────────────────────────────

  "exchangeCallback" should "store encrypted token and set challenge_ready when upgrade flow succeeds" in {
    val stateRepo = StubOAuthLinkStateRepository()
    val linkRepo  = StubExternalAccountLinkRepository()
    val cipher    = testCipher()
    val userId    = UUID.randomUUID()
    val ls        = OAuthLinkState(
      state                = "cr-state",
      userId               = userId,
      codeVerifier         = "verifier",
      redirectAfterSuccess = "http://localhost/settings?lichess=upgraded",
      expiresAt            = Instant.now().plusSeconds(600),
      createdAt            = Instant.now(),
      targetCapability     = "challenge_ready"
    )
    stateRepo.store("cr-state") = ls
    val svc    = makeService(stateRepo = stateRepo, linkRepo = linkRepo,
                             client = lichessMockClient("bob_chess"), cipher = Some(cipher))
    val result = svc.exchangeCallback("code", "cr-state").unsafeRunSync()

    val link = result.value
    link.capability         shouldBe "challenge_ready"
    link.verified           shouldBe true
    link.verificationSource shouldBe "OAuthPKCE"
    link.tokenEncrypted.isDefined shouldBe true
    link.tokenScopes        shouldBe Some(testConfig.upgradeScope)
    link.tokenStoredAt.isDefined  shouldBe true
    // Token is stored encrypted, not as plaintext
    link.tokenEncrypted.fold(fail("tokenEncrypted is None")) { enc =>
      new String(enc, "UTF-8") should not include "tok-test"
      cipher.decrypt(enc).value shouldBe "tok-test"
    }
  }

  it should "fail closed for challenge_ready callback when no encryption key is configured" in {
    val stateRepo = StubOAuthLinkStateRepository()
    val linkRepo  = StubExternalAccountLinkRepository()
    val userId    = UUID.randomUUID()
    val ls        = OAuthLinkState(
      state                = "cr-nokey-state",
      userId               = userId,
      codeVerifier         = "verifier",
      redirectAfterSuccess = "http://localhost/settings?lichess=upgraded",
      expiresAt            = Instant.now().plusSeconds(600),
      createdAt            = Instant.now(),
      targetCapability     = "challenge_ready"
    )
    stateRepo.store("cr-nokey-state") = ls
    val svc    = makeService(stateRepo = stateRepo, linkRepo = linkRepo,
                             client = lichessMockClient("alice"), cipher = None)
    val result = svc.exchangeCallback("code", "cr-nokey-state").unsafeRunSync()

    result.isLeft        shouldBe true
    result.left.value    shouldBe "token_encryption_not_configured"
    linkRepo.store       shouldBe empty
  }

  it should "fail with lichess_account_mismatch when OAuth account differs from existing link" in {
    val stateRepo = StubOAuthLinkStateRepository()
    val linkRepo  = StubExternalAccountLinkRepository()
    val cipher    = testCipher()
    val userId    = UUID.randomUUID()
    val existing  = ExternalAccountLink(
      linkId             = UUID.randomUUID(),
      userId             = userId,
      provider           = "Lichess",
      externalId         = Some("olduser"),
      externalUsername   = "olduser",
      verified           = true,
      verificationSource = "OAuthPKCE",
      linkedAt           = Instant.now(),
      capability         = "identity_only",
      tokenEncrypted     = None,
      tokenScopes        = None,
      tokenStoredAt      = None
    )
    linkRepo.store((userId, "Lichess")) = existing
    val ls = OAuthLinkState(
      state                = "mismatch-state",
      userId               = userId,
      codeVerifier         = "verifier",
      redirectAfterSuccess = "http://localhost/settings?lichess=upgraded",
      expiresAt            = Instant.now().plusSeconds(600),
      createdAt            = Instant.now(),
      targetCapability     = "challenge_ready"
    )
    stateRepo.store("mismatch-state") = ls
    // Lichess returns "newuser" but existing link is "olduser"
    val svc    = makeService(stateRepo = stateRepo, linkRepo = linkRepo,
                             client = lichessMockClient("newuser"), cipher = Some(cipher))
    val result = svc.exchangeCallback("code", "mismatch-state").unsafeRunSync()

    result.isLeft           shouldBe true
    result.left.value       shouldBe "lichess_account_mismatch"
    // Existing link must be unchanged
    linkRepo.store((userId, "Lichess")).externalUsername shouldBe "olduser"
    linkRepo.store((userId, "Lichess")).capability       shouldBe "identity_only"
  }

  it should "clear token fields when identity_only callback runs over an existing challenge_ready link" in {
    val stateRepo = StubOAuthLinkStateRepository()
    val linkRepo  = StubExternalAccountLinkRepository()
    val cipher    = testCipher()
    val userId    = UUID.randomUUID()
    val crLink    = ExternalAccountLink(
      linkId             = UUID.randomUUID(),
      userId             = userId,
      provider           = "Lichess",
      externalId         = Some("alice"),
      externalUsername   = "alice",
      verified           = true,
      verificationSource = "OAuthPKCE",
      linkedAt           = Instant.now(),
      capability         = "challenge_ready",
      tokenEncrypted     = Some(cipher.encrypt("old-token").value),
      tokenScopes        = Some("challenge:write preference:read"),
      tokenStoredAt      = Some(Instant.now())
    )
    linkRepo.store((userId, "Lichess")) = crLink
    val ls = OAuthLinkState(
      state                = "relink-state",
      userId               = userId,
      codeVerifier         = "verifier",
      redirectAfterSuccess = "http://localhost/settings?lichess=linked",
      expiresAt            = Instant.now().plusSeconds(600),
      createdAt            = Instant.now(),
      targetCapability     = "identity_only"
    )
    stateRepo.store("relink-state") = ls
    val svc    = makeService(stateRepo = stateRepo, linkRepo = linkRepo,
                             client = lichessMockClient("alice"), cipher = Some(cipher))
    val result = svc.exchangeCallback("code", "relink-state").unsafeRunSync()

    val link = result.value
    link.capability      shouldBe "identity_only"
    link.tokenEncrypted  shouldBe None
    link.tokenScopes     shouldBe None
    link.tokenStoredAt   shouldBe None
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

    override def findByLichessUsername(username: String): Either[String, Option[ExternalAccountLink]] =
      Right(None)

    override def delete(userId: UUID, provider: String): Either[String, Unit] =
      store.remove((userId, provider))
      Right(())
