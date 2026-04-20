package chess.query.game

import chess.application.query.game.GameView
import chess.application.session.model.SessionIds.GameId
import chess.domain.model.{Color, GameStatus, Move, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, EnPassantState, GameState, GameStateFactory}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests for [[GameView.toGameState]] and the canonical position fields added
 *  to [[GameView]] to support exact FEN reconstruction.
 *
 *  These tests verify the round-trip property:
 *    `GameView.fromState(id, state).toGameState` preserves every field that
 *    [[chess.notation.fen.FenSerializer]] uses: piece placement, active color,
 *    castling rights, en passant target, halfmove clock, fullmove number.
 */
class GameViewToGameStateSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val anyId = GameId.random()

  // ── fromState preserves canonical position fields ────────────────────────────

  "GameView.fromState" should "preserve castling rights from the source GameState" in {
    val state = GameStateFactory.initial()
    val view  = GameView.fromState(anyId, state)

    view.castlingRights shouldBe CastlingRights.full
  }

  it should "preserve reduced castling rights" in {
    val state = GameStateFactory.initial().copy(
      castlingRights = CastlingRights(whiteKingSide = false, whiteQueenSide = true, blackKingSide = true, blackQueenSide = false)
    )
    val view = GameView.fromState(anyId, state)

    view.castlingRights.whiteKingSide  shouldBe false
    view.castlingRights.whiteQueenSide shouldBe true
    view.castlingRights.blackKingSide  shouldBe true
    view.castlingRights.blackQueenSide shouldBe false
  }

  it should "preserve None en passant state when absent" in {
    val view = GameView.fromState(anyId, GameStateFactory.initial())
    view.enPassantState shouldBe None
  }

  it should "preserve en passant state when present" in {
    val targetSq    = Position.fromAlgebraic("e3").value
    val pawnSq      = Position.fromAlgebraic("e4").value
    val ep          = EnPassantState(targetSq, pawnSq, Color.White)
    val state       = GameStateFactory.initial().copy(enPassantState = Some(ep))
    val view        = GameView.fromState(anyId, state)

    view.enPassantState shouldBe Some(ep)
  }

  it should "preserve halfmove clock and fullmove number" in {
    val state = GameStateFactory.initial().copy(halfmoveClock = 7, fullmoveNumber = 3)
    val view  = GameView.fromState(anyId, state)

    view.halfmoveClock  shouldBe 7
    view.fullmoveNumber shouldBe 3
  }

  // ── toGameState round-trip ───────────────────────────────────────────────────

  "GameView.toGameState" should "reproduce the original GameState from the initial position" in {
    val original = GameStateFactory.initial()
    val rebuilt  = GameView.fromState(anyId, original).toGameState

    rebuilt.currentPlayer  shouldBe original.currentPlayer
    rebuilt.status         shouldBe original.status
    rebuilt.castlingRights shouldBe original.castlingRights
    rebuilt.enPassantState shouldBe original.enPassantState
    rebuilt.halfmoveClock  shouldBe original.halfmoveClock
    rebuilt.fullmoveNumber shouldBe original.fullmoveNumber
    rebuilt.moveHistory    shouldBe original.moveHistory
    rebuilt.board.pieces.toSet shouldBe original.board.pieces.toSet
  }

  it should "reproduce exact board piece placement after round-trip" in {
    val original = GameStateFactory.initial()
    val rebuilt  = GameView.fromState(anyId, original).toGameState

    // Verify each piece from the original is present at the same square
    original.board.pieces.foreach { case (pos, piece) =>
      rebuilt.board.pieceAt(pos) shouldBe Some(piece)
    }
    rebuilt.board.pieces should have size original.board.pieces.size
  }

  it should "round-trip a state with no castling rights and an en passant target" in {
    val ep    = EnPassantState(
      Position.fromAlgebraic("d6").value,
      Position.fromAlgebraic("d5").value,
      Color.Black
    )
    val original = GameStateFactory.initial().copy(
      castlingRights = CastlingRights.none,
      enPassantState = Some(ep),
      halfmoveClock  = 0,
      fullmoveNumber = 5
    )
    val rebuilt = GameView.fromState(anyId, original).toGameState

    rebuilt.castlingRights shouldBe CastlingRights.none
    rebuilt.enPassantState shouldBe Some(ep)
    rebuilt.fullmoveNumber shouldBe 5
  }

  it should "round-trip a terminal (Checkmate) state" in {
    val original = GameStateFactory.initial().copy(
      status = GameStatus.Checkmate(Color.White)
    )
    val rebuilt = GameView.fromState(anyId, original).toGameState

    rebuilt.status shouldBe GameStatus.Checkmate(Color.White)
  }
