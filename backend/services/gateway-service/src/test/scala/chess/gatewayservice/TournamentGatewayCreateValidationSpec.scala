package chess.gatewayservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import GatewayJwtExtractor.GatewayJwtClaims

class TournamentGatewayCreateValidationSpec extends AnyFlatSpec with Matchers:

  private val devClaims = GatewayJwtClaims(sub = "dev-sub", preferredUsername = "dev", email = None)

  // Creates a routes instance backed by a mock TS that returns the given detail body on GET
  // and the given statuses for POST .../join and DELETE ....
  private def mkRoutes(
    tsDetailBody: String,
    joinStatus: Status = Status.Ok,
    deleteStatus: Status = Status.Ok
  ): TournamentGatewayRoutes =
    val mockApp = HttpRoutes.of[IO] {
      case POST -> Root / "api" / "auth" / "register" =>
        Ok("""{"id":"bot_slow","name":"searchess-stockfish-slow","token":"fake-jwt"}""")
      case GET -> Root / "api" / "tournament" / _ =>
        Ok(tsDetailBody)
      case POST -> Root / "api" / "tournament" / _ / "join" =>
        IO.pure(Response[IO](status = joinStatus))
      case DELETE -> Root / "api" / "tournament" / _ =>
        IO.pure(Response[IO](status = deleteStatus))
    }.orNotFound
    val mockClient  = Client.fromHttpApp(mockApp)
    val config      = GatewayServiceConfig("localhost", 8087, "http://ts", "http://us", true, "dev", None)
    val authBridge  = new TournamentAuthBridge(
      mockClient, config.tournamentServerUrl, new TournamentJwtCache(), new TournamentJwtCache()
    )
    new TournamentGatewayRoutes(mockClient, config, authBridge)

  // Shared routes for pure-parsing tests (no HTTP needed).
  private val plainRoutes = mkRoutes("""{}""")

  // ── parseHostBotCreate ─────────────────────────────────────────────────────────

  "parseHostBotCreate" should "extract bot IDs and build form body without the bot fields" in {
    val body =
      """{"name":"Test","nbRounds":5,"clockLimit":300,"clockIncrement":0,"rated":true,""" +
      """"format":"swiss","tournamentServerBotId":"bot_slow","tournamentServerBotName":"searchess-stockfish-slow"}"""
    val result = plainRoutes.parseHostBotCreate(body)
    result.isRight shouldBe true
    val (botId, botName, formBody) = result.getOrElse(fail("expected Right"))
    botId   shouldBe "bot_slow"
    botName shouldBe "searchess-stockfish-slow"
    formBody should include("name=Test")
    formBody should not include "tournamentServerBotId"
    formBody should not include "tournamentServerBotName"
  }

  it should "fail when tournamentServerBotId is missing from the request" in {
    plainRoutes.parseHostBotCreate("""{"name":"Test","nbRounds":5}""").isLeft shouldBe true
  }

  it should "fail when tournamentServerBotId is present but empty" in {
    val body = """{"name":"Test","tournamentServerBotId":"","tournamentServerBotName":"x"}"""
    plainRoutes.parseHostBotCreate(body).isLeft shouldBe true
  }

  it should "fail on invalid JSON" in {
    plainRoutes.parseHostBotCreate("not json").isLeft shouldBe true
  }

  // ── parseTournamentIdFromBody ──────────────────────────────────────────────────

  "parseTournamentIdFromBody" should "extract the tournament ID from a TS create response" in {
    val body = """{"id":"abc123","fullName":"Test Tournament","nbPlayers":0}"""
    plainRoutes.parseTournamentIdFromBody(body) shouldBe Some("abc123")
  }

  it should "return None for invalid JSON" in {
    plainRoutes.parseTournamentIdFromBody("not json") shouldBe None
  }

  it should "return None when the id field is absent" in {
    plainRoutes.parseTournamentIdFromBody("""{"fullName":"x"}""") shouldBe None
  }

  // ── verifyAfterCreate ─────────────────────────────────────────────────────────

  "verifyAfterCreate" should "succeed when TS already has exactly the selected host bot" in {
    val tsDetail =
      """{"id":"t1","standing":{"players":[{"bot":{"id":"bot_slow","name":"searchess-stockfish-slow"}}]}}"""
    val routes = mkRoutes(tsDetail)
    val result = routes.verifyAfterCreate("t1", "bot_slow", "searchess-stockfish-slow", devClaims)
      .unsafeRunSync()
    result shouldBe Right(())
  }

  it should "join the host bot and succeed when TS has no bots after create" in {
    val tsDetail = """{"id":"t1","standing":{"players":[]}}"""
    val routes   = mkRoutes(tsDetail, joinStatus = Status.Ok)
    val result   = routes.verifyAfterCreate("t1", "bot_slow", "searchess-stockfish-slow", devClaims)
      .unsafeRunSync()
    result shouldBe Right(())
  }

  it should "fail and delete the tournament when TS contains an unexpected bot after create" in {
    val tsDetail =
      """{"id":"t1","standing":{"players":[{"bot":{"id":"bot_now","name":"NowChess Expert"}}]}}"""
    val routes = mkRoutes(tsDetail, deleteStatus = Status.Ok)
    val result = routes.verifyAfterCreate("t1", "bot_slow", "searchess-stockfish-slow", devClaims)
      .unsafeRunSync()
    result.isLeft shouldBe true
    result.left.toOption.get should include("NowChess Expert")
    result.left.toOption.get should include("deleted")
  }

  it should "fail and delete when host bot join fails after empty create" in {
    val tsDetail = """{"id":"t1","standing":{"players":[]}}"""
    val routes   = mkRoutes(tsDetail, joinStatus = Status.InternalServerError)
    val result   = routes.verifyAfterCreate("t1", "bot_slow", "searchess-stockfish-slow", devClaims)
      .unsafeRunSync()
    result.isLeft shouldBe true
    result.left.toOption.get should include("deleted")
  }
