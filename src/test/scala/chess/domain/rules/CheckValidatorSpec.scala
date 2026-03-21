package chess.domain.rules

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.domain.error.DomainError
import chess.domain.model.*
import org.scalatest.EitherValues

class CheckValidatorSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def at(alg: String): Position = Position.fromAlgebraic(alg).value

  private def boardWith(placements: (String, Piece)*): Board =
    placements.foldLeft(Board.empty) { case (b, (sq, p)) => b.place(at(sq), p) }

  private val whiteKing  = Piece(Color.White, PieceType.King)
  private val blackKing  = Piece(Color.Black, PieceType.King)
  private val whiteRook  = Piece(Color.White, PieceType.Rook)
  private val blackRook  = Piece(Color.Black, PieceType.Rook)
  private val whitePawn  = Piece(Color.White, PieceType.Pawn)
  private val blackPawn  = Piece(Color.Black, PieceType.Pawn)
  private val whiteBishop = Piece(Color.White, PieceType.Bishop)
  private val blackBishop = Piece(Color.Black, PieceType.Bishop)
  private val blackKnight = Piece(Color.Black, PieceType.Knight)
  private val whiteQueen  = Piece(Color.White, PieceType.Queen)

  // ── isKingInCheck: positive cases ─────────────────────────────────────────

  "CheckValidator.isKingInCheck" should "detect check from a rook on the same file" in {
    val board = boardWith("e1" -> whiteKing, "e8" -> blackRook)
    CheckValidator.isKingInCheck(board, Color.White) shouldBe true
  }

  it should "detect check from a rook on the same rank" in {
    val board = boardWith("e4" -> whiteKing, "a4" -> blackRook)
    CheckValidator.isKingInCheck(board, Color.White) shouldBe true
  }

  it should "detect check from a bishop on a diagonal" in {
    // white king e1, black bishop h4 — diagonal: e1→f2→g3→h4, all clear
    val board = boardWith("e1" -> whiteKing, "h4" -> blackBishop)
    CheckValidator.isKingInCheck(board, Color.White) shouldBe true
  }

  it should "detect check from a knight" in {
    // knight on f6 attacks e4 via (1,2) L-move
    val board = boardWith("e4" -> whiteKing, "f6" -> blackKnight)
    CheckValidator.isKingInCheck(board, Color.White) shouldBe true
  }

  it should "detect check from a pawn (black pawn attacks diagonally toward lower rank)" in {
    // black pawn at f5 attacks e4 (df=1, dr=-1)
    val board = boardWith("e4" -> whiteKing, "f5" -> blackPawn)
    CheckValidator.isKingInCheck(board, Color.White) shouldBe true
  }

  it should "detect check from a pawn (white pawn attacks diagonally toward higher rank)" in {
    // white pawn at d5 attacks e6 (df=1, dr=+1)
    val board = boardWith("e6" -> blackKing, "d5" -> whitePawn)
    CheckValidator.isKingInCheck(board, Color.Black) shouldBe true
  }

  // ── isKingInCheck: negative cases ─────────────────────────────────────────

  it should "return false when the king is not in check" in {
    val board = boardWith("e1" -> whiteKing, "e8" -> blackRook, "e4" -> whiteRook)
    // white rook on e4 blocks the black rook's attack
    CheckValidator.isKingInCheck(board, Color.White) shouldBe false
  }

  it should "return false when there is no king on the board" in {
    val board = boardWith("a1" -> whiteRook)
    CheckValidator.isKingInCheck(board, Color.White) shouldBe false
  }

  it should "not flag check from a blocked rook path" in {
    val board = boardWith("e1" -> whiteKing, "e5" -> blackRook, "e3" -> whitePawn)
    CheckValidator.isKingInCheck(board, Color.White) shouldBe false
  }

  it should "not flag check from a pawn moving straight (pawns do not attack forward)" in {
    // black pawn directly in front of white king — pawns move forward, not attack forward
    val board = boardWith("e1" -> whiteKing, "e2" -> blackPawn)
    CheckValidator.isKingInCheck(board, Color.White) shouldBe false
  }

  // ── isSquareAttacked ───────────────────────────────────────────────────────

  "CheckValidator.isSquareAttacked" should "identify an attacked square" in {
    val board = boardWith("a1" -> blackRook)
    CheckValidator.isSquareAttacked(board, at("h1"), Color.Black) shouldBe true
  }

  it should "return false for an unattacked square" in {
    val board = boardWith("a1" -> blackRook)
    CheckValidator.isSquareAttacked(board, at("h2"), Color.Black) shouldBe false
  }

  // ── MoveApplier integration: king-safety enforcement ──────────────────────

  "MoveApplier.applyMove" should "reject a move that leaves the king in check (pinned piece)" in {
    // white king e1, white rook e4 (pinned), black rook e8
    val board = boardWith("e1" -> whiteKing, "e4" -> whiteRook, "e8" -> blackRook)
    // moving the pinned rook sideways exposes the king
    MoveApplier.applyMove(board, Move(at("e4"), at("d4"))).left.value shouldBe DomainError.KingInCheck
  }

  it should "reject moving the king into an attacked square" in {
    // white king e1, black rook d8 — d1 is on the d-file, attacked
    val board = boardWith("e1" -> whiteKing, "d8" -> blackRook)
    MoveApplier.applyMove(board, Move(at("e1"), at("d1"))).left.value shouldBe DomainError.KingInCheck
  }

  it should "allow a move that blocks an existing check" in {
    // white king e1 in check from black rook e8; white rook a4 blocks by moving to e4
    val board = boardWith("e1" -> whiteKing, "e8" -> blackRook, "a4" -> whiteRook)
    val MoveResult.Applied(result) = MoveApplier.applyMove(board, Move(at("a4"), at("e4"))).value: @unchecked
    result.pieceAt(at("e4")) shouldBe Some(whiteRook)
  }

  it should "allow capturing the attacking piece to resolve check" in {
    // white king e1, black rook e4 gives check (e2/e3 clear); white rook h4 captures
    val board = boardWith("e1" -> whiteKing, "e4" -> blackRook, "h4" -> whiteRook)
    val MoveResult.Applied(result) = MoveApplier.applyMove(board, Move(at("h4"), at("e4"))).value: @unchecked
    result.pieceAt(at("e4")) shouldBe Some(whiteRook)
  }

  it should "allow the king to escape check by moving to a safe square" in {
    // white king e1 in check from black rook e8; king escapes to d1 (d-file is clear)
    val board = boardWith("e1" -> whiteKing, "e8" -> blackRook)
    val MoveResult.Applied(result) = MoveApplier.applyMove(board, Move(at("e1"), at("d1"))).value: @unchecked
    result.pieceAt(at("d1")) shouldBe Some(whiteKing)
  }
