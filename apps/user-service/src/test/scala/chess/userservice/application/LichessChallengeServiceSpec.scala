package chess.userservice.application

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.userservice.domain.ExternalAccountLink
import fs2.Stream
import org.http4s.*
import org.http4s.client.Client
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class LichessChallengeServiceSpec extends AnyFlatSpec with Matchers with EitherValues:

  // 32-byte all-zero AES key for deterministic encryption in tests
  private val testKey    = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
  private val testCipher = LichessTokenCipher.fromBase64Key(testKey)
    .getOrElse(fail("cipher init failed for test key"))

  private val botConfig = LichessChallengeBotConfig(
    botUsername         = "arutepsu2",
    challengeApiBaseUrl = "https://lichess.org/api/challenge"
  )

  private val userId  = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val validReq = CreateChallengeRequest()

  private def makeLink(capability: String, token: Option[String] = None): ExternalAccountLink =
    ExternalAccountLink(
      linkId             = UUID.randomUUID(),
      userId             = userId,
      provider           = "Lichess",
      externalId         = None,
      externalUsername   = "testuser",
      verified           = false,
      verificationSource = "OAuth",
      linkedAt           = Instant.now(),
      capability         = capability,
      tokenEncrypted     = token.map(t => testCipher.encrypt(t)
        .getOrElse(fail("test token encryption failed"))),
      tokenScopes        = token.map(_ => "challenge:write"),
      tokenStoredAt      = token.map(_ => Instant.now())
    )

  private def stubbedClient(resp: IO[Response[IO]]): Client[IO] =
    Client.fromHttpApp(HttpRoutes.of[IO] { case _ => resp }.orNotFound)

  private final case class CapturedLichessRequest(
      method: Method,
      path: String,
      authorization: Option[String],
      contentType: Option[String],
      body: String
  )

  private def capturingClient(
      captured: AtomicReference[Option[CapturedLichessRequest]],
      resp: Response[IO]
  ): Client[IO] =
    Client.fromHttpApp(HttpRoutes.of[IO] { case req =>
      req.bodyText.compile.string.map { body =>
        captured.set(Some(CapturedLichessRequest(
          method        = req.method,
          path          = req.uri.path.renderString,
          authorization = req.headers.get(CIString("Authorization")).map(_.head.value),
          contentType   = req.headers.get(CIString("Content-Type")).map(_.head.value),
          body          = body
        )))
        resp
      }
    }.orNotFound)

  // ── Scenario 1: no Lichess link ──────────────────────────────────────────

  "createChallengeToBot" should "return Left(no_lichess_link) when user has no Lichess link" in {
    val repo   = StubLinkRepository(None)
    val svc    = LichessChallengeService(repo, Some(testCipher), stubbedClient(IO.pure(Response[IO](Status.Ok))), botConfig)
    val result = svc.createChallengeToBot(userId, validReq).unsafeRunSync()
    result shouldBe Left("no_lichess_link")
  }

  // ── Scenario 2: identity_only capability ─────────────────────────────────

  it should "return Left(no_challenge_ready_capability) when link has identity_only capability" in {
    val repo   = StubLinkRepository(Some(makeLink("identity_only")))
    val svc    = LichessChallengeService(repo, Some(testCipher), stubbedClient(IO.pure(Response[IO](Status.Ok))), botConfig)
    val result = svc.createChallengeToBot(userId, validReq).unsafeRunSync()
    result shouldBe Left("no_challenge_ready_capability")
  }

  // ── Scenario 3: challenge_ready but no stored token ───────────────────────

  it should "return Left(no_stored_lichess_token) when link has challenge_ready but no token" in {
    val repo   = StubLinkRepository(Some(makeLink("challenge_ready", token = None)))
    val svc    = LichessChallengeService(repo, Some(testCipher), stubbedClient(IO.pure(Response[IO](Status.Ok))), botConfig)
    val result = svc.createChallengeToBot(userId, validReq).unsafeRunSync()
    result shouldBe Left("no_stored_lichess_token")
  }

  // ── Scenario 4: cipher not configured ────────────────────────────────────

  it should "return Left(token_encryption_not_configured) when cipher is None" in {
    val repo   = StubLinkRepository(Some(makeLink("challenge_ready", token = Some("lio_test"))))
    val svc    = LichessChallengeService(repo, cipher = None, stubbedClient(IO.pure(Response[IO](Status.Ok))), botConfig)
    val result = svc.createChallengeToBot(userId, validReq).unsafeRunSync()
    result shouldBe Left("token_encryption_not_configured")
  }

  // ── Scenario 5: valid success ────────────────────────────────────────────

  it should "return Right(challengeId, url) for a valid challenge_ready link and 200 Lichess response" in {
    val repo    = StubLinkRepository(Some(makeLink("challenge_ready", token = Some("lio_test"))))
    val json    = """{"challenge":{"id":"abc123","url":"https://lichess.org/abc123"}}"""
    val client  = stubbedClient(IO.pure(Response[IO](
      status = Status.Ok,
      body   = Stream.emits(json.getBytes("UTF-8")).covary[IO]
    )))
    val svc    = LichessChallengeService(repo, Some(testCipher), client, botConfig)
    val result = svc.createChallengeToBot(userId, validReq).unsafeRunSync()
    result shouldBe Right(CreateChallengeResult("abc123", "https://lichess.org/abc123"))
  }

  it should "send the Lichess challenge request as authenticated form data" in {
    val repo     = StubLinkRepository(Some(makeLink("challenge_ready", token = Some("lio_test"))))
    val json     = """{"challenge":{"id":"abc","url":"https://lichess.org/abc"}}"""
    val captured = new AtomicReference[Option[CapturedLichessRequest]](None)
    val client   = capturingClient(
      captured,
      Response[IO](
        status = Status.Ok,
        body   = Stream.emits(json.getBytes("UTF-8")).covary[IO]
      )
    )
    val svc    = LichessChallengeService(repo, Some(testCipher), client, botConfig)
    val result = svc.createChallengeToBot(userId, validReq).unsafeRunSync()

    result shouldBe Right(CreateChallengeResult("abc", "https://lichess.org/abc"))

    val request = captured.get().getOrElse(fail("expected Lichess request to be captured"))
    request.method        shouldBe Method.POST
    request.path          shouldBe "/api/challenge/arutepsu2"
    request.authorization shouldBe Some("Bearer lio_test")
    request.contentType.getOrElse(fail("expected Content-Type header")) should startWith("application/x-www-form-urlencoded")
    request.body should include("rated=false")
    request.body should include("variant=standard")
    request.body should include("clock.limit=300")
    request.body should include("clock.increment=3")
    request.body should include("color=random")
    request.body should not include("clockSeconds")
    request.body should not include("clockIncrement")
  }

  it should "return Right(challengeId, url) for a top-level Lichess challenge response" in {
    val repo    = StubLinkRepository(Some(makeLink("challenge_ready", token = Some("lio_test"))))
    val json    = """{"id":"abc","url":"https://lichess.org/abc"}"""
    val client  = stubbedClient(IO.pure(Response[IO](
      status = Status.Ok,
      body   = Stream.emits(json.getBytes("UTF-8")).covary[IO]
    )))
    val svc    = LichessChallengeService(repo, Some(testCipher), client, botConfig)
    val result = svc.createChallengeToBot(userId, validReq).unsafeRunSync()
    result shouldBe Right(CreateChallengeResult("abc", "https://lichess.org/abc"))
  }

  "getReadOnlyGameState" should "return Left(no_lichess_link) when user has no Lichess link" in {
    val repo   = StubLinkRepository(None)
    val svc    = LichessChallengeService(repo, Some(testCipher), stubbedClient(IO.pure(Response[IO](Status.Ok))), botConfig)
    val result = svc.getReadOnlyGameState(userId, "abc123").unsafeRunSync()
    result shouldBe Left("no_lichess_link")
  }

  it should "return Left(no_lichess_game_capability) when link has identity_only capability" in {
    val repo   = StubLinkRepository(Some(makeLink("identity_only", token = Some("lio_test"))))
    val svc    = LichessChallengeService(repo, Some(testCipher), stubbedClient(IO.pure(Response[IO](Status.Ok))), botConfig)
    val result = svc.getReadOnlyGameState(userId, "abc123").unsafeRunSync()
    result shouldBe Left("no_lichess_game_capability")
  }

  it should "return Left(no_stored_lichess_token) when link has no token" in {
    val repo   = StubLinkRepository(Some(makeLink("challenge_ready", token = None)))
    val svc    = LichessChallengeService(repo, Some(testCipher), stubbedClient(IO.pure(Response[IO](Status.Ok))), botConfig)
    val result = svc.getReadOnlyGameState(userId, "abc123").unsafeRunSync()
    result shouldBe Left("no_stored_lichess_token")
  }

  it should "return Left(lichess_token_expired) and expire the link when Lichess game fetch returns 401" in {
    val link   = makeLink("challenge_ready", token = Some("lio_test"))
    val repo   = StubLinkRepository(Some(link))
    val client = stubbedClient(IO.pure(Response[IO](Status.Unauthorized)))
    val svc    = LichessChallengeService(repo, Some(testCipher), client, botConfig)
    val result = svc.getReadOnlyGameState(userId, "abc123").unsafeRunSync()
    result                                     shouldBe Left("lichess_token_expired")
    repo.lastUpdated.map(_.capability)         shouldBe Some("expired")
    repo.lastUpdated.flatMap(_.tokenEncrypted) shouldBe None
  }

  it should "return a normalized safe DTO for a successful Lichess game state response" in {
    val repo   = StubLinkRepository(Some(makeLink("challenge_ready", token = Some("lio_test"))))
    val json   =
      """{
        |  "id":"abc123",
        |  "status":"started",
        |  "fen":"rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
        |  "moves":"e2e4",
        |  "players":{
        |    "white":{"user":{"name":"testuser"}},
        |    "black":{"user":{"name":"arutepsu2"}}
        |  }
        |}""".stripMargin
    val client = stubbedClient(IO.pure(Response[IO](
      status = Status.Ok,
      body   = Stream.emits(json.getBytes("UTF-8")).covary[IO]
    )))
    val svc    = LichessChallengeService(repo, Some(testCipher), client, botConfig)
    val result = svc.getReadOnlyGameState(userId, "abc123").unsafeRunSync().value

    result.gameId                 shouldBe "abc123"
    result.status                 shouldBe "started"
    result.fen.getOrElse(fail("expected FEN")) should include(" b ")
    result.moves                  shouldBe "e2e4"
    result.white.username         shouldBe "testuser"
    result.white.isSearchessBot   shouldBe false
    result.black.username         shouldBe "arutepsu2"
    result.black.isSearchessBot   shouldBe true
    result.sideToMove             shouldBe "black"
    result.userColor              shouldBe Some("white")
    result.botColor               shouldBe Some("black")
  }

  "submitMove" should "return Left(invalid_move_format) for non-UCI moves" in {
    val repo = StubLinkRepository(Some(makeLink("challenge_ready", token = Some("lio_test"))))
    val svc  = LichessChallengeService(repo, Some(testCipher), stubbedClient(IO.pure(Response[IO](Status.Ok))), botConfig)

    List("e2e", "hello", "e9e4", "e7e8x").foreach { move =>
      svc.submitMove(userId, "abc123", move).unsafeRunSync() shouldBe Left("invalid_move_format")
    }
  }

  it should "return Left(no_lichess_link) when user has no Lichess link" in {
    val repo   = StubLinkRepository(None)
    val svc    = LichessChallengeService(repo, Some(testCipher), stubbedClient(IO.pure(Response[IO](Status.Ok))), botConfig)
    val result = svc.submitMove(userId, "abc123", "e2e4").unsafeRunSync()
    result shouldBe Left("no_lichess_link")
  }

  it should "return Left(no_lichess_game_capability) when link has identity_only capability" in {
    val repo   = StubLinkRepository(Some(makeLink("identity_only", token = Some("lio_test"))))
    val svc    = LichessChallengeService(repo, Some(testCipher), stubbedClient(IO.pure(Response[IO](Status.Ok))), botConfig)
    val result = svc.submitMove(userId, "abc123", "e2e4").unsafeRunSync()
    result shouldBe Left("no_lichess_game_capability")
  }

  it should "return Left(no_stored_lichess_token) when link has no token" in {
    val repo   = StubLinkRepository(Some(makeLink("challenge_ready", token = None)))
    val svc    = LichessChallengeService(repo, Some(testCipher), stubbedClient(IO.pure(Response[IO](Status.Ok))), botConfig)
    val result = svc.submitMove(userId, "abc123", "e2e4").unsafeRunSync()
    result shouldBe Left("no_stored_lichess_token")
  }

  it should "return Left(token_encryption_not_configured) when cipher is missing" in {
    val repo   = StubLinkRepository(Some(makeLink("challenge_ready", token = Some("lio_test"))))
    val svc    = LichessChallengeService(repo, cipher = None, stubbedClient(IO.pure(Response[IO](Status.Ok))), botConfig)
    val result = svc.submitMove(userId, "abc123", "e2e4").unsafeRunSync()
    result shouldBe Left("token_encryption_not_configured")
  }

  it should "post an authenticated Board API move and return accepted true" in {
    val repo     = StubLinkRepository(Some(makeLink("challenge_ready", token = Some("lio_test"))))
    val captured = new AtomicReference[Option[CapturedLichessRequest]](None)
    val client   = capturingClient(captured, Response[IO](status = Status.Ok))
    val svc      = LichessChallengeService(repo, Some(testCipher), client, botConfig)
    val result   = svc.submitMove(userId, "abc123", "e2e4").unsafeRunSync()

    result shouldBe Right(SubmitLichessMoveResult("abc123", "e2e4", accepted = true))

    val request = captured.get().getOrElse(fail("expected Lichess request to be captured"))
    request.method        shouldBe Method.POST
    request.path          shouldBe "/api/board/game/abc123/move/e2e4"
    request.authorization shouldBe Some("Bearer lio_test")
  }

  it should "return Left(illegal_or_invalid_move) when Lichess returns 400 or 422" in {
    List(Status.BadRequest, Status.UnprocessableEntity).foreach { status =>
      val repo   = StubLinkRepository(Some(makeLink("challenge_ready", token = Some("lio_test"))))
      val client = stubbedClient(IO.pure(Response[IO](status = status)))
      val svc    = LichessChallengeService(repo, Some(testCipher), client, botConfig)
      svc.submitMove(userId, "abc123", "e2e4").unsafeRunSync() shouldBe Left("illegal_or_invalid_move")
      repo.lastUpdated shouldBe None
    }
  }

  it should "return Left(lichess_token_expired) and expire the link when Lichess move returns 401 or 403" in {
    List(Status.Unauthorized, Status.Forbidden).foreach { status =>
      val link   = makeLink("challenge_ready", token = Some("lio_test"))
      val repo   = StubLinkRepository(Some(link))
      val client = stubbedClient(IO.pure(Response[IO](status = status)))
      val svc    = LichessChallengeService(repo, Some(testCipher), client, botConfig)
      val result = svc.submitMove(userId, "abc123", "e2e4").unsafeRunSync()
      result                                     shouldBe Left("lichess_token_expired")
      repo.lastUpdated.map(_.capability)         shouldBe Some("expired")
      repo.lastUpdated.flatMap(_.tokenEncrypted) shouldBe None
    }
  }

  it should "return Left(lichess_move_failed) when Lichess returns 500" in {
    val repo   = StubLinkRepository(Some(makeLink("challenge_ready", token = Some("lio_test"))))
    val client = stubbedClient(IO.pure(Response[IO](Status.InternalServerError)))
    val svc    = LichessChallengeService(repo, Some(testCipher), client, botConfig)
    val result = svc.submitMove(userId, "abc123", "e2e4").unsafeRunSync()
    result           shouldBe Left("lichess_move_failed")
    repo.lastUpdated shouldBe None
  }

  // ── Scenario 6: validation failures ─────────────────────────────────────

  it should "return Left(invalid_challenge_request:...) when rated is true" in {
    val repo   = StubLinkRepository(Some(makeLink("challenge_ready", token = Some("tok"))))
    val svc    = LichessChallengeService(repo, Some(testCipher), stubbedClient(IO.pure(Response[IO](Status.Ok))), botConfig)
    val result = svc.createChallengeToBot(userId, validReq.copy(rated = true)).unsafeRunSync()
    result.left.value should startWith("invalid_challenge_request:")
    result.left.value should include("rated")
  }

  it should "return Left(invalid_challenge_request:...) when variant is chess960" in {
    val repo   = StubLinkRepository(Some(makeLink("challenge_ready", token = Some("tok"))))
    val svc    = LichessChallengeService(repo, Some(testCipher), stubbedClient(IO.pure(Response[IO](Status.Ok))), botConfig)
    val result = svc.createChallengeToBot(userId, validReq.copy(variant = "chess960")).unsafeRunSync()
    result.left.value should startWith("invalid_challenge_request:")
    result.left.value should include("variant")
  }

  it should "return Left(invalid_challenge_request:...) when clockSeconds is below minimum" in {
    val repo   = StubLinkRepository(Some(makeLink("challenge_ready", token = Some("tok"))))
    val svc    = LichessChallengeService(repo, Some(testCipher), stubbedClient(IO.pure(Response[IO](Status.Ok))), botConfig)
    val result = svc.createChallengeToBot(userId, validReq.copy(clockSeconds = 60)).unsafeRunSync()
    result.left.value should startWith("invalid_challenge_request:")
    result.left.value should include("clockSeconds")
  }

  it should "return Left(invalid_challenge_request:...) when clockIncrement exceeds maximum" in {
    val repo   = StubLinkRepository(Some(makeLink("challenge_ready", token = Some("tok"))))
    val svc    = LichessChallengeService(repo, Some(testCipher), stubbedClient(IO.pure(Response[IO](Status.Ok))), botConfig)
    val result = svc.createChallengeToBot(userId, validReq.copy(clockIncrement = 15)).unsafeRunSync()
    result.left.value should startWith("invalid_challenge_request:")
    result.left.value should include("clockIncrement")
  }

  // ── Scenario 7: Lichess 401 → link expired ───────────────────────────────

  it should "return Left(lichess_token_expired) and expire the link when Lichess returns 401" in {
    val link    = makeLink("challenge_ready", token = Some("lio_test"))
    val repo    = StubLinkRepository(Some(link))
    val client  = stubbedClient(IO.pure(Response[IO](Status.Unauthorized)))
    val svc     = LichessChallengeService(repo, Some(testCipher), client, botConfig)
    val result  = svc.createChallengeToBot(userId, validReq).unsafeRunSync()
    result                                         shouldBe Left("lichess_token_expired")
    repo.lastUpdated.map(_.capability)             shouldBe Some("expired")
    repo.lastUpdated.flatMap(_.tokenEncrypted)     shouldBe None
    repo.lastUpdated.flatMap(_.tokenScopes)        shouldBe None
  }

  it should "return Left(lichess_token_expired) and expire the link when Lichess returns 403" in {
    val link    = makeLink("challenge_ready", token = Some("lio_test"))
    val repo    = StubLinkRepository(Some(link))
    val client  = stubbedClient(IO.pure(Response[IO](Status.Forbidden)))
    val svc     = LichessChallengeService(repo, Some(testCipher), client, botConfig)
    val result  = svc.createChallengeToBot(userId, validReq).unsafeRunSync()
    result                                     shouldBe Left("lichess_token_expired")
    repo.lastUpdated.map(_.capability)         shouldBe Some("expired")
  }

  // ── Scenario 8: Lichess 4xx/5xx non-auth → no expiry ────────────────────

  it should "return Left(lichess_challenge_failed) and not expire the link when Lichess returns 422" in {
    val link    = makeLink("challenge_ready", token = Some("lio_test"))
    val repo    = StubLinkRepository(Some(link))
    val client  = stubbedClient(IO.pure(Response[IO](Status.UnprocessableEntity)))
    val svc     = LichessChallengeService(repo, Some(testCipher), client, botConfig)
    val result  = svc.createChallengeToBot(userId, validReq).unsafeRunSync()
    result                shouldBe Left("lichess_challenge_failed")
    repo.lastUpdated      shouldBe None
  }

  it should "return Left(lichess_challenge_failed) and not expire the link when Lichess returns 500" in {
    val link    = makeLink("challenge_ready", token = Some("lio_test"))
    val repo    = StubLinkRepository(Some(link))
    val client  = stubbedClient(IO.pure(Response[IO](Status.InternalServerError)))
    val svc     = LichessChallengeService(repo, Some(testCipher), client, botConfig)
    val result  = svc.createChallengeToBot(userId, validReq).unsafeRunSync()
    result           shouldBe Left("lichess_challenge_failed")
    repo.lastUpdated shouldBe None
  }

  // ── Scenario extra: expiry DB update fails but result is still safe ──────

  it should "return Left(lichess_token_expired) even when the expiry DB update fails" in {
    val link   = makeLink("challenge_ready", token = Some("lio_test"))
    val repo   = StubLinkRepository(Some(link), failUpdate = true)
    val client = stubbedClient(IO.pure(Response[IO](Status.Unauthorized)))
    val svc    = LichessChallengeService(repo, Some(testCipher), client, botConfig)
    val result = svc.createChallengeToBot(userId, validReq).unsafeRunSync()
    result shouldBe Left("lichess_token_expired")
  }

  // ── Stub repository ──────────────────────────────────────────────────────

  private class StubLinkRepository(
      initial: Option[ExternalAccountLink],
      failUpdate: Boolean = false
  ) extends ExternalAccountLinkRepository:
    private val updatedRef = new AtomicReference[Option[ExternalAccountLink]](None)

    def lastUpdated: Option[ExternalAccountLink] = updatedRef.get()

    override def findByUserAndProvider(userId: UUID, provider: String): Either[String, Option[ExternalAccountLink]] =
      Right(initial.filter(l => l.userId == userId && l.provider == provider))

    override def update(link: ExternalAccountLink): Either[String, Unit] =
      if failUpdate then Left("db_connection_failed")
      else
        updatedRef.set(Some(link))
        Right(())

    override def findAllByUserId(userId: UUID): Either[String, List[ExternalAccountLink]]                    = Right(Nil)
    override def insert(link: ExternalAccountLink): Either[String, Unit]                                     = Right(())
    override def findByLichessUsername(username: String): Either[String, Option[ExternalAccountLink]]        = Right(None)
    override def delete(userId: UUID, provider: String): Either[String, Unit]                                = Right(())
