package chess.lichessbridge

import cats.effect.unsafe.implicits.global
import chess.lichessbridge.LichessError.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests for the submitMove capability of LichessClient implementations. */
class LichessClientSubmitMoveSpec extends AnyFlatSpec with Matchers:

  // ── ControllableLichessClient — submitMove configuration ──────────────────────

  "ControllableLichessClient.submitMove" should "return Right(()) by default" in {
    val client = ControllableLichessClient()
    client.submitMove("tok", "g1", "e2e4").unsafeRunSync() shouldBe Right(())
  }

  it should "return the configured Right(()) success" in {
    val client = ControllableLichessClient(submitMoveResult = Right(()))
    client.submitMove("tok", "g1", "e2e4").unsafeRunSync() shouldBe Right(())
  }

  it should "return Left(UnexpectedResponse(400, ...)) for an illegal move" in {
    val client = ControllableLichessClient(
      submitMoveResult = Left(UnexpectedResponse(400, "Move rejected: illegal move or game already finished."))
    )
    client.submitMove("tok", "g1", "a1a2").unsafeRunSync() match
      case Left(UnexpectedResponse(400, _)) => succeed
      case other                            => fail(s"Expected Left(UnexpectedResponse(400,...)) but got $other")
  }

  it should "return Left(Unauthorized) when token is rejected" in {
    val client = ControllableLichessClient(
      submitMoveResult = Left(Unauthorized("Token rejected by Lichess."))
    )
    client.submitMove("bad-tok", "g1", "e2e4").unsafeRunSync() match
      case Left(Unauthorized(_)) => succeed
      case other                 => fail(s"Expected Left(Unauthorized) but got $other")
  }

  it should "return Left(RateLimited) when rate limit is hit" in {
    val client = ControllableLichessClient(
      submitMoveResult = Left(RateLimited(Some(60)))
    )
    client.submitMove("tok", "g1", "e2e4").unsafeRunSync() match
      case Left(RateLimited(Some(60))) => succeed
      case other                       => fail(s"Expected Left(RateLimited(Some(60))) but got $other")
  }

  it should "return Left(NetworkError) on a network failure" in {
    val client = ControllableLichessClient(
      submitMoveResult = Left(NetworkError("connection refused"))
    )
    client.submitMove("tok", "g1", "e2e4").unsafeRunSync() match
      case Left(NetworkError(_)) => succeed
      case other                 => fail(s"Expected Left(NetworkError) but got $other")
  }

  // ── LichessHttpClient: mapStatus helper (status-code routing logic) ──────────

  "LichessHttpClient.mapStatus" should "map 401 to Left(Unauthorized)" in {
    LichessHttpClient.mapStatus(401, "", None) shouldBe Left(Unauthorized("Token rejected by Lichess."))
  }

  it should "map 403 to Left(Unauthorized)" in {
    LichessHttpClient.mapStatus(403, "", None) shouldBe Left(Unauthorized("Token rejected by Lichess."))
  }

  it should "map 429 to Left(RateLimited) with retry-after" in {
    LichessHttpClient.mapStatus(429, "", Some(30)) shouldBe Left(RateLimited(Some(30)))
  }

  it should "map 500 to Left(UnexpectedResponse)" in {
    LichessHttpClient.mapStatus(500, "server error", None) match
      case Left(UnexpectedResponse(500, _)) => succeed
      case other                            => fail(s"Expected Left(UnexpectedResponse(500,...)) but got $other")
  }

  // ── Token safety ─────────────────────────────────────────────────────────────

  "submitMove error messages" should "never expose the token value" in {
    val sensitiveToken = "lip_secretTokenValue12345"
    val client = ControllableLichessClient(
      submitMoveResult = Left(NetworkError("connection refused"))
    )
    client.submitMove(sensitiveToken, "g1", "e2e4").unsafeRunSync() match
      case Left(err) =>
        err.toString should not include sensitiveToken
      case Right(_) =>
        succeed
  }
