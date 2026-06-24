package chess.gatewayservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import org.http4s.*
import org.http4s.client.Client
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import GatewayJwtExtractor.GatewayJwtClaims

class TournamentGatewayStartValidationSpec extends AnyFlatSpec with Matchers:

  private val config = GatewayServiceConfig(
    host                = "localhost",
    port                = 8087,
    tournamentServerUrl = "http://ts",
    userServiceUrl      = "http://us",
    authDisabled        = true,
    devUserName         = "dev"
  )

  private def makeRoutes(userServiceHandler: PartialFunction[Request[IO], IO[Response[IO]]] = PartialFunction.empty): TournamentGatewayRoutes =
    val client = Client.fromHttpApp(
      HttpRoutes.of[IO](userServiceHandler).orNotFound
    )
    val authBridge = new TournamentAuthBridge(
      client, config.tournamentServerUrl,
      new TournamentJwtCache(), new TournamentJwtCache()
    )
    new TournamentGatewayRoutes(client, config, authBridge)

  private val routes: TournamentGatewayRoutes = makeRoutes()

  private def jsonResp(status: Status, body: String): IO[Response[IO]] =
    IO.pure(Response[IO](
      status = status,
      body   = Stream.emits(body.getBytes("UTF-8")).covary[IO]
    ))

  private def searchessBody(bots: (String, String)*): String =
    val items = bots.map { case (id, name) =>
      s"""{"tournamentServerBotId":"$id","tournamentServerBotName":"$name","tournamentId":"t1","searchessUserId":"u1","displayName":"User","searchessBotId":null,"searchessCatalogBotId":null,"tournamentServerUserId":null,"joinedAt":"2026-01-01T00:00:00Z"}"""
    }.mkString(",")
    s"""{"tournamentId":"t1","participants":[$items]}"""

  private def tsBody(nbPlayers: Int, players: (String, String)*): String =
    val items = players.map { case (id, name) =>
      s"""{"bot":{"id":"$id","name":"$name"}}"""
    }.mkString(",")
    s"""{"nbPlayers":$nbPlayers,"standing":{"players":[$items]}}"""

  // Test 1 — TS has an extra bot that Searchess never chose: this is an external participant (allowed).
  // The Tournament Server is public; external users may join directly via its API.
  // Start is only blocked when a Searchess-chosen bot is missing, not when TS has extra bots.
  "checkParticipantMismatch" should "return None (allow start) when Tournament Server has an external participant" in {
    val sb = searchessBody(
      "bot_slow" -> "searchess-stockfish-slow",
      "bot_deep" -> "searchess-stockfish-depth-3"
    )
    val tb = tsBody(3,
      "bot_slow" -> "searchess-stockfish-slow",
      "bot_deep" -> "searchess-stockfish-depth-3",
      "bot_now"  -> "NowChess Expert"
    )

    // All Searchess bots are present in TS; NowChess Expert is an external participant — allowed.
    routes.checkParticipantMismatch(sb, tb) shouldBe None
  }

  // Test 2 — a Searchess-chosen bot never joined the Tournament Server
  it should "return 409 when a Searchess participant bot is missing from Tournament Server" in {
    val sb = searchessBody(
      "bot_slow" -> "searchess-stockfish-slow",
      "bot_deep" -> "searchess-stockfish-depth-3"
    )
    val tb = tsBody(1,
      "bot_slow" -> "searchess-stockfish-slow"
      // bot_deep is absent
    )

    val result = routes.checkParticipantMismatch(sb, tb)
    result should not be empty

    val resp = result.get
    resp.status shouldBe Status.Conflict
    val body = resp.bodyText.compile.string.unsafeRunSync()
    body should include("START_PARTICIPANT_MISMATCH")
    body should include("searchess-stockfish-depth-3")
    body should include("missingFromTs")
  }

  // Test 3 — sets match exactly; start is allowed
  it should "return None when Tournament Server bots exactly match Searchess participants" in {
    val sb = searchessBody(
      "bot_slow" -> "searchess-stockfish-slow",
      "bot_deep" -> "searchess-stockfish-depth-3"
    )
    val tb = tsBody(2,
      "bot_slow" -> "searchess-stockfish-slow",
      "bot_deep" -> "searchess-stockfish-depth-3"
    )

    routes.checkParticipantMismatch(sb, tb) shouldBe None
  }

  // Test 4 — nbPlayers disagrees with actual player list; standing.players drives the check
  it should "pass when standing.players match even if nbPlayers is inconsistent" in {
    val sb = searchessBody(
      "bot_slow" -> "searchess-stockfish-slow",
      "bot_deep" -> "searchess-stockfish-depth-3"
    )
    // nbPlayers claims 99 but the actual player list is exactly what Searchess chose
    val tb = tsBody(99,
      "bot_slow" -> "searchess-stockfish-slow",
      "bot_deep" -> "searchess-stockfish-depth-3"
    )

    routes.checkParticipantMismatch(sb, tb) shouldBe None
  }

  // ── parseHostMetadata ─────────────────────────────────────────────────────────

  "parseHostMetadata" should "return Some with all fields when body is complete" in {
    val body =
      """{"tournamentId":"t1","hostSearchessUserId":"u1","hostKeycloakSub":"sub-abc",
         "hostDisplayName":"Alice","createdAt":"2026-01-01T00:00:00Z",
         "directorTournamentServerBotId":"bot_abc123",
         "directorTournamentServerBotName":"StockfishBot"}"""
    val result = routes.parseHostMetadata(body)
    result should not be empty
    val meta = result.get
    meta.hostKeycloakSub shouldBe "sub-abc"
    meta.directorBotId   shouldBe Some("bot_abc123")
    meta.directorBotName shouldBe Some("StockfishBot")
  }

  it should "return Some with None director fields when they are JSON null" in {
    val body =
      """{"tournamentId":"t1","hostSearchessUserId":"u1","hostKeycloakSub":"sub-old",
         "hostDisplayName":"Bob","createdAt":"2026-01-01T00:00:00Z",
         "directorTournamentServerBotId":null,"directorTournamentServerBotName":null}"""
    val result = routes.parseHostMetadata(body)
    result should not be empty
    val meta = result.get
    meta.hostKeycloakSub shouldBe "sub-old"
    meta.directorBotId   shouldBe None
    meta.directorBotName shouldBe None
  }

  it should "return None when body is not valid JSON" in {
    routes.parseHostMetadata("not json") shouldBe None
  }

  it should "return None when body is empty" in {
    routes.parseHostMetadata("") shouldBe None
  }

  // ── resolveDirectorAuth ───────────────────────────────────────────────────────

  "resolveDirectorAuth" should "return Right(Some(botId, botName)) when caller matches host and bot fields are present" in {
    val hostBody =
      """{"tournamentId":"t1","hostSearchessUserId":"u1","hostKeycloakSub":"sub-host",
         "hostDisplayName":"Host","createdAt":"2026-01-01T00:00:00Z",
         "directorTournamentServerBotId":"bot_dir","directorTournamentServerBotName":"DirBot"}"""
    val r = makeRoutes {
      case GET -> Root / "users" / "tournaments" / "t1" / "host" => jsonResp(Status.Ok, hostBody)
    }
    val claims = GatewayJwtClaims(sub = "sub-host", preferredUsername = "hostuser", email = None)
    val result = r.resolveDirectorAuth("t1", claims).unsafeRunSync()
    result shouldBe Right(Some("bot_dir" -> "DirBot"))
  }

  it should "return Left(403 SEARCHESS_NOT_HOST) when caller sub does not match hostKeycloakSub" in {
    val hostBody =
      """{"tournamentId":"t1","hostSearchessUserId":"u1","hostKeycloakSub":"sub-host",
         "hostDisplayName":"Host","createdAt":"2026-01-01T00:00:00Z",
         "directorTournamentServerBotId":"bot_dir","directorTournamentServerBotName":"DirBot"}"""
    val r = makeRoutes {
      case GET -> Root / "users" / "tournaments" / "t1" / "host" => jsonResp(Status.Ok, hostBody)
    }
    val claims = GatewayJwtClaims(sub = "sub-attacker", preferredUsername = "attacker", email = None)
    val result = r.resolveDirectorAuth("t1", claims).unsafeRunSync()
    result.isLeft shouldBe true
    val resp     = result.left.get
    resp.status  shouldBe Status.Forbidden
    val body     = resp.bodyText.compile.string.unsafeRunSync()
    body should include("SEARCHESS_NOT_HOST")
  }

  it should "return Right(None) when host endpoint returns 404 (old tournament)" in {
    val r = makeRoutes {
      case GET -> Root / "users" / "tournaments" / "old-t" / "host" =>
        jsonResp(Status.NotFound, """{"code":"NOT_FOUND"}""")
    }
    val claims = GatewayJwtClaims(sub = "sub-host", preferredUsername = "hostuser", email = None)
    val result = r.resolveDirectorAuth("old-t", claims).unsafeRunSync()
    result shouldBe Right(None)
  }

  it should "return Right(None) when host metadata has null director fields (transition case)" in {
    val hostBody =
      """{"tournamentId":"t1","hostSearchessUserId":"u1","hostKeycloakSub":"sub-host",
         "hostDisplayName":"Host","createdAt":"2026-01-01T00:00:00Z",
         "directorTournamentServerBotId":null,"directorTournamentServerBotName":null}"""
    val r = makeRoutes {
      case GET -> Root / "users" / "tournaments" / "t1" / "host" => jsonResp(Status.Ok, hostBody)
    }
    val claims = GatewayJwtClaims(sub = "sub-host", preferredUsername = "hostuser", email = None)
    val result = r.resolveDirectorAuth("t1", claims).unsafeRunSync()
    result shouldBe Right(None)
  }
