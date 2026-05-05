package chess.domain.rules

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.domain.model.{Board, Color, Move, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, GameStateFactory}

class LegalMoveGeneratorSpec extends AnyFlatSpec with Matchers:

  private def algPos(alg: String): Position =
    Position.fromAlgebraic(alg).getOrElse(throw AssertionError(s"Bad algebraic: $alg"))

  private def freshState = GameStateFactory.initial()

  // ── legalTargetsFrom ──────────────────────────────────────────────────────

  "LegalMoveGenerator.legalTargetsFrom" should "return two squares for an e-file pawn at start" in {
    val state = freshState
    val e2 = algPos("e2")
    val e3 = algPos("e3")
    val e4 = algPos("e4")
    LegalMoveGenerator.legalTargetsFrom(state, e2) shouldBe Set(e3, e4)
  }

  it should "return an empty set when there is no current-player piece at the position" in {
    val state = freshState
    LegalMoveGenerator.legalTargetsFrom(state, algPos("e4")) shouldBe empty
  }

  it should "return an empty set for an opponent's piece" in {
    val state = freshState // White to move
    LegalMoveGenerator.legalTargetsFrom(state, algPos("e7")) shouldBe empty
  }

  // ── legalMovesFrom ────────────────────────────────────────────────────────

  "LegalMoveGenerator.legalMovesFrom" should "return Move objects for a pawn on its starting rank" in {
    val state = freshState
    val e2 = algPos("e2")
    val moves = LegalMoveGenerator.legalMovesFrom(state, e2)
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
      PieceType.Queen,
      PieceType.Rook,
      PieceType.Bishop,
      PieceType.Knight
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

  // ── Sliding-attacker pin / king-safety via isPathClear ─────────────────────

  it should "reject a rook move that leaves the king on the rook's vacated file exposed to an opponent rook" in {
    // White rook on e4 shields the white king on e1 from a black rook on e8.
    // Moving the white rook off the e-file should not be legal.
    val whiteKing = Piece(Color.White, PieceType.King)
    val whiteRook = Piece(Color.White, PieceType.Rook)
    val blackRook = Piece(Color.Black, PieceType.Rook)
    val blackKing = Piece(Color.Black, PieceType.King)
    val board = Board.empty
      .place(algPos("e1"), whiteKing)
      .place(algPos("e4"), whiteRook)
      .place(algPos("e8"), blackRook)
      .place(algPos("a8"), blackKing)
    val state = freshState.copy(board = board)
    val moves = LegalMoveGenerator.legalMovesFrom(state, algPos("e4"))
    // The rook may only move along the e-file (staying between king and attacker)
    moves.map(_.to.toString) should not contain oneOf("d4", "f4", "c4", "g4", "h4", "b4")
    moves.map(_.to) should contain(algPos("e8")) // can capture attacker
    moves.map(_.to) should contain(algPos("e5")) // can stay on file
  }

  it should "reject a bishop move that leaves the king on the diagonal exposed to an opponent bishop" in {
    // White bishop on d4 shields the white king on a1 from a black bishop on g7.
    // Moving the bishop off the a1-h8 diagonal should not be legal.
    val whiteKing = Piece(Color.White, PieceType.King)
    val whiteBishop = Piece(Color.White, PieceType.Bishop)
    val blackBishop = Piece(Color.Black, PieceType.Bishop)
    val blackKing = Piece(Color.Black, PieceType.King)
    val board = Board.empty
      .place(algPos("a1"), whiteKing)
      .place(algPos("d4"), whiteBishop)
      .place(algPos("g7"), blackBishop)
      .place(algPos("h1"), blackKing)
    val state = freshState.copy(board = board)
    val moves = LegalMoveGenerator.legalMovesFrom(state, algPos("d4"))
    // Bishop may only move along the a1-h8 diagonal
    moves.map(_.to.toString) should not contain oneOf("c5", "e3", "c3", "e5")
    moves.map(_.to) should contain(algPos("g7")) // can capture attacker
  }
