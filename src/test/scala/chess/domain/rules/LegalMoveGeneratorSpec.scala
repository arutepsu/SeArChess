package chess.domain.rules

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.domain.model.{Board, Color, Move, Piece, PieceType, Position}
import chess.domain.state.CastlingRights
import chess.application.ChessService

class LegalMoveGeneratorSpec extends AnyFlatSpec with Matchers:

  private def algPos(alg: String): Position =
    Position.fromAlgebraic(alg).getOrElse(throw AssertionError(s"Bad algebraic: $alg"))

  private def freshState = ChessService.createNewGame()

  // ── legalTargetsFrom ──────────────────────────────────────────────────────

  "LegalMoveGenerator.legalTargetsFrom" should "return two squares for an e-file pawn at start" in {
    val state = freshState
    val e2    = algPos("e2")
    val e3    = algPos("e3")
    val e4    = algPos("e4")
    LegalMoveGenerator.legalTargetsFrom(state, e2) shouldBe Set(e3, e4)
  }

  it should "return an empty set when there is no current-player piece at the position" in {
    val state = freshState
    LegalMoveGenerator.legalTargetsFrom(state, algPos("e4")) shouldBe empty
  }

  it should "return an empty set for an opponent's piece" in {
    val state = freshState   // White to move
    LegalMoveGenerator.legalTargetsFrom(state, algPos("e7")) shouldBe empty
  }

  // ── legalMovesFrom ────────────────────────────────────────────────────────

  "LegalMoveGenerator.legalMovesFrom" should "return Move objects for a pawn on its starting rank" in {
    val state  = freshState
    val e2     = algPos("e2")
    val moves  = LegalMoveGenerator.legalMovesFrom(state, e2)
    moves.map(_.to) shouldBe Set(algPos("e3"), algPos("e4"))
    moves.foreach(_.promotion shouldBe None)
  }

  it should "expand a promotion to four moves (Q, R, B, N)" in {
    val a7 = algPos("a7")
    val a8 = algPos("a8")
    val e8 = algPos("e8")
    val board = Board.empty
      .place(a7, Piece(Color.White, PieceType.Pawn))
      .place(algPos("a1"), Piece(Color.White, PieceType.King))
      .place(e8, Piece(Color.Black, PieceType.King))
    val state = freshState.copy(board = board)
    val moves = LegalMoveGenerator.legalMovesFrom(state, a7)
    val promoMoves = moves.filter(_.to == a8)
    promoMoves should have size 4
    promoMoves.flatMap(_.promotion) shouldBe Set(
      PieceType.Queen, PieceType.Rook, PieceType.Bishop, PieceType.Knight
    )
  }

  it should "return an empty set for an opponent's piece" in {
    val state = freshState
    LegalMoveGenerator.legalMovesFrom(state, algPos("e7")) shouldBe empty
  }

  it should "return an empty set when there is no piece at the position" in {
    val state = freshState
    LegalMoveGenerator.legalMovesFrom(state, algPos("e4")) shouldBe empty
  }
