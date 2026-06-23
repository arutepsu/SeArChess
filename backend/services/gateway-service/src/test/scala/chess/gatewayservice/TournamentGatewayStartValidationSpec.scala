package chess.gatewayservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.*
import org.http4s.client.Client
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TournamentGatewayStartValidationSpec extends AnyFlatSpec with Matchers:

  private val routes: TournamentGatewayRoutes =
    val mockClient = Client.fromHttpApp(HttpRoutes.empty[IO].orNotFound)
    val config = GatewayServiceConfig(
      host                = "localhost",
      port                = 8087,
      tournamentServerUrl = "http://ts",
      userServiceUrl      = "http://us",
      authDisabled        = true,
      devUserName         = "dev"
    )
    val authBridge = new TournamentAuthBridge(
      mockClient, config.tournamentServerUrl,
      new TournamentJwtCache(), new TournamentJwtCache()
    )
    new TournamentGatewayRoutes(mockClient, config, authBridge)

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
