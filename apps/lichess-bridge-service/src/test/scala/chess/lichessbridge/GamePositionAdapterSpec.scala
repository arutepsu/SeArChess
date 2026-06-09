package chess.lichessbridge

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GamePositionAdapterSpec extends AnyFlatSpec with Matchers:

  // ── toGameState ──────────────────────────────────────────────────────────────

  "GamePositionAdapter.toGameState" should "parse startpos to a White-to-move initial state" in {
    GamePositionAdapter.toGameState("startpos", "").map { s =>
      import chess.domain.model.Color
      s.currentPlayer
    } shouldBe Right(chess.domain.model.Color.White)
  }

  it should "treat blank initialFen as startpos" in {
    GamePositionAdapter.toGameState("", "").isRight shouldBe true
  }

  it should "switch to Black after one move e2e4" in {
    GamePositionAdapter.toGameState("startpos", "e2e4").map { s =>
      import chess.domain.model.Color
      s.currentPlayer
    } shouldBe Right(chess.domain.model.Color.Black)
  }

  it should "replay two moves and return to White to move" in {
    GamePositionAdapter.toGameState("startpos", "e2e4 e7e5").map { s =>
      import chess.domain.model.Color
      s.currentPlayer
    } shouldBe Right(chess.domain.model.Color.White)
  }

  it should "return Left for an invalid FEN string" in {
    GamePositionAdapter.toGameState("definitely not a fen", "").isLeft shouldBe true
  }

  it should "return Left for an invalid UCI move (too short)" in {
    GamePositionAdapter.toGameState("startpos", "e2").isLeft shouldBe true
  }

  it should "return Left for an illegal UCI move from startpos" in {
    GamePositionAdapter.toGameState("startpos", "e2e5").isLeft shouldBe true
  }

  // ── toLegalMoveDtos ───────────────────────────────────────────────────────────

  "GamePositionAdapter.toLegalMoveDtos" should "return 20 legal moves for the starting position" in {
    GamePositionAdapter.toGameState("startpos", "") match
      case Right(state) =>
        GamePositionAdapter.toLegalMoveDtos(state).size shouldBe 20
      case Left(err) =>
        fail(s"Expected Right but got Left($err)")
  }

  it should "return a non-empty list for any mid-game position" in {
    GamePositionAdapter.toGameState("startpos", "e2e4 e7e5 d2d4") match
      case Right(state) =>
        GamePositionAdapter.toLegalMoveDtos(state).size should be > 0
      case Left(err) =>
        fail(s"Expected Right but got Left($err)")
  }

  it should "produce DTOs with non-empty from and to strings" in {
    GamePositionAdapter.toGameState("startpos", "") match
      case Right(state) =>
        val dtos = GamePositionAdapter.toLegalMoveDtos(state)
        dtos.foreach { dto =>
          dto.from should not be empty
          dto.to   should not be empty
        }
      case Left(err) =>
        fail(s"Expected Right but got Left($err)")
  }

  // ── toCurrentFen ─────────────────────────────────────────────────────────────

  "GamePositionAdapter.toCurrentFen" should "return a FEN containing expected piece characters for startpos" in {
    GamePositionAdapter.toGameState("startpos", "") match
      case Right(state) =>
        val fen = GamePositionAdapter.toCurrentFen(state)
        fen should include("rnbqkbnr")
        fen should include("RNBQKBNR")
        fen should include("w")
      case Left(err) =>
        fail(s"Expected Right but got Left($err)")
  }

  // ── toSideToMove ─────────────────────────────────────────────────────────────

  "GamePositionAdapter.toSideToMove" should "return 'White' for the initial position" in {
    GamePositionAdapter.toGameState("startpos", "") match
      case Right(state) => GamePositionAdapter.toSideToMove(state) shouldBe "White"
      case Left(err)    => fail(s"Expected Right but got Left($err)")
  }

  it should "return 'Black' after one White move" in {
    GamePositionAdapter.toGameState("startpos", "e2e4") match
      case Right(state) => GamePositionAdapter.toSideToMove(state) shouldBe "Black"
      case Left(err)    => fail(s"Expected Right but got Left($err)")
  }
