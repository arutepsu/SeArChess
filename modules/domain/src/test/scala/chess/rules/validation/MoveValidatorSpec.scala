package chess.domain.rules.validation

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import chess.domain.error.DomainError
import chess.domain.model.*

class MoveValidatorSpec extends AnyFlatSpec with Matchers with EitherValues:

  // ── helpers ────────────────────────────────────────────────────────────────

  private def at(alg: String): Position = Position.fromAlgebraic(alg).value
  private def mv(from: String, to: String): Move = Move(at(from), at(to))

  private def validate(board: Board, piece: Piece, from: String, to: String) =
    MoveValidator.validate(board, piece, mv(from, to))

  private def boardWith(placements: (String, Piece)*): Board =
    placements.foldLeft(Board.empty) { case (b, (sq, p)) => b.place(at(sq), p) }

  // ── General rules ──────────────────────────────────────────────────────────

  "MoveValidator" should "reject a move to the same square" in {
    val piece = Piece(Color.White, PieceType.Knight)
    validate(boardWith("e4" -> piece), piece, "e4", "e4").left.value shouldBe DomainError.SameSquare
  }

  it should "return false from canAttack when from and to are the same square" in {
    val piece = Piece(Color.White, PieceType.Queen)
    val pos = at("e4")
    MoveValidator.canAttack(boardWith("e4" -> piece), piece, pos, pos) shouldBe false
  }

  it should "reject a move onto a square occupied by the same color" in {
    val knight = Piece(Color.White, PieceType.Knight)
    val rook = Piece(Color.White, PieceType.Rook)
    val board = boardWith("e4" -> knight, "f6" -> rook)
    validate(board, knight, "e4", "f6").left.value shouldBe a[DomainError.OccupiedByOwnPiece]
  }

  // ── Rook ───────────────────────────────────────────────────────────────────

  "Rook" should "move horizontally to an empty square" in {
    val rook = Piece(Color.White, PieceType.Rook)
    val board = boardWith("a1" -> rook)
    validate(board, rook, "a1", "h1").value shouldBe ()
  }

  it should "move vertically to an empty square" in {
    val rook = Piece(Color.White, PieceType.Rook)
    val board = boardWith("a1" -> rook)
    validate(board, rook, "a1", "a8").value shouldBe ()
  }

  it should "fail on a diagonal move" in {
    val rook = Piece(Color.White, PieceType.Rook)
    val board = boardWith("a1" -> rook)
    validate(board, rook, "a1", "d4").left.value shouldBe a[DomainError.IllegalMove]
  }

  it should "fail when the horizontal path is blocked" in {
    val rook = Piece(Color.White, PieceType.Rook)
    val blocker = Piece(Color.Black, PieceType.Pawn)
    val board = boardWith("a1" -> rook, "d1" -> blocker)
    validate(board, rook, "a1", "h1").left.value shouldBe a[DomainError.BlockedPath]
  }

  it should "fail when the vertical path is blocked" in {
    val rook = Piece(Color.White, PieceType.Rook)
    val blocker = Piece(Color.Black, PieceType.Pawn)
    val board = boardWith("a1" -> rook, "a5" -> blocker)
    validate(board, rook, "a1", "a8").left.value shouldBe a[DomainError.BlockedPath]
  }

  // ── Bishop ─────────────────────────────────────────────────────────────────

  "Bishop" should "move diagonally to an empty square" in {
    val bishop = Piece(Color.White, PieceType.Bishop)
    val board = boardWith("a1" -> bishop)
    validate(board, bishop, "a1", "h8").value shouldBe ()
  }

  it should "fail on a straight move" in {
    val bishop = Piece(Color.White, PieceType.Bishop)
    val board = boardWith("a1" -> bishop)
    validate(board, bishop, "a1", "a5").left.value shouldBe a[DomainError.IllegalMove]
  }

  it should "fail when the diagonal path is blocked" in {
    val bishop = Piece(Color.White, PieceType.Bishop)
    val blocker = Piece(Color.Black, PieceType.Pawn)
    val board = boardWith("a1" -> bishop, "d4" -> blocker)
    validate(board, bishop, "a1", "h8").left.value shouldBe a[DomainError.BlockedPath]
  }

  // ── Queen ──────────────────────────────────────────────────────────────────

  "Queen" should "move straight to an empty square" in {
    val queen = Piece(Color.White, PieceType.Queen)
    val board = boardWith("d4" -> queen)
    validate(board, queen, "d4", "d8").value shouldBe ()
  }

  it should "move diagonally to an empty square" in {
    val queen = Piece(Color.White, PieceType.Queen)
    val board = boardWith("d4" -> queen)
    validate(board, queen, "d4", "h8").value shouldBe ()
  }

  it should "fail on a knight-like move" in {
    val queen = Piece(Color.White, PieceType.Queen)
    val board = boardWith("d4" -> queen)
    validate(board, queen, "d4", "e6").left.value shouldBe a[DomainError.IllegalMove]
  }

  it should "fail when the straight path is blocked" in {
    val queen = Piece(Color.White, PieceType.Queen)
    val blocker = Piece(Color.Black, PieceType.Pawn)
    val board = boardWith("d1" -> queen, "d5" -> blocker)
    validate(board, queen, "d1", "d8").left.value shouldBe a[DomainError.BlockedPath]
  }

  it should "fail when the diagonal path is blocked" in {
    val queen = Piece(Color.White, PieceType.Queen)
    val blocker = Piece(Color.Black, PieceType.Pawn)
    val board = boardWith("a1" -> queen, "d4" -> blocker)
    validate(board, queen, "a1", "h8").left.value shouldBe a[DomainError.BlockedPath]
  }

  // ── Knight ─────────────────────────────────────────────────────────────────

  "Knight" should "accept an L-move (2+1)" in {
    val knight = Piece(Color.White, PieceType.Knight)
    val board = boardWith("e4" -> knight)
    validate(board, knight, "e4", "f6").value shouldBe ()
  }

  it should "accept an L-move (1+2)" in {
    val knight = Piece(Color.White, PieceType.Knight)
    val board = boardWith("e4" -> knight)
    validate(board, knight, "e4", "g5").value shouldBe ()
  }

  it should "fail on a non-L move" in {
    val knight = Piece(Color.White, PieceType.Knight)
    val board = boardWith("e4" -> knight)
    validate(board, knight, "e4", "e6").left.value shouldBe a[DomainError.IllegalMove]
  }

  it should "jump over intervening pieces" in {
    val knight = Piece(Color.White, PieceType.Knight)
    val blocker = Piece(Color.Black, PieceType.Pawn)
    // fill every adjacent square
    val board = boardWith(
      "e4" -> knight,
      "e5" -> blocker,
      "e6" -> blocker,
      "f5" -> blocker,
      "d5" -> blocker,
      "f4" -> blocker,
      "d4" -> blocker
    )
    validate(board, knight, "e4", "f6").value shouldBe ()
  }

  // ── King ───────────────────────────────────────────────────────────────────

  "King" should "move one square in any direction" in {
    val king = Piece(Color.White, PieceType.King)
    val board = boardWith("e4" -> king)
    val neighbors = List("d3", "e3", "f3", "d4", "f4", "d5", "e5", "f5")
    neighbors.foreach { sq =>
      validate(board, king, "e4", sq).value shouldBe ()
    }
  }

  it should "fail when moving two squares" in {
    val king = Piece(Color.White, PieceType.King)
    val board = boardWith("e4" -> king)
    validate(board, king, "e4", "e6").left.value shouldBe a[DomainError.IllegalMove]
  }

  // ── Pawn (white) ───────────────────────────────────────────────────────────

  "White pawn" should "move one square forward into an empty square" in {
    val pawn = Piece(Color.White, PieceType.Pawn)
    val board = boardWith("e2" -> pawn)
    validate(board, pawn, "e2", "e3").value shouldBe ()
  }

  it should "move two squares forward from the starting rank when path is clear" in {
    val pawn = Piece(Color.White, PieceType.Pawn)
    val board = boardWith("e2" -> pawn)
    validate(board, pawn, "e2", "e4").value shouldBe ()
  }

  it should "fail the double move when the intermediate square is occupied" in {
    val pawn = Piece(Color.White, PieceType.Pawn)
    val blocker = Piece(Color.Black, PieceType.Pawn)
    val board = boardWith("e2" -> pawn, "e3" -> blocker)
    validate(board, pawn, "e2", "e4").left.value shouldBe a[DomainError.BlockedPath]
  }

  it should "fail the double move when the target square is occupied" in {
    val pawn = Piece(Color.White, PieceType.Pawn)
    val blocker = Piece(Color.Black, PieceType.Pawn)
    val board = boardWith("e2" -> pawn, "e4" -> blocker)
    validate(board, pawn, "e2", "e4").left.value shouldBe a[DomainError.BlockedPath]
  }

  it should "fail the double move from a non-starting rank" in {
    val pawn = Piece(Color.White, PieceType.Pawn)
    val board = boardWith("e3" -> pawn)
    validate(board, pawn, "e3", "e5").left.value shouldBe a[DomainError.IllegalMove]
  }

  it should "fail when moving straight forward into an occupied square" in {
    val pawn = Piece(Color.White, PieceType.Pawn)
    val blocker = Piece(Color.Black, PieceType.Pawn)
    val board = boardWith("e2" -> pawn, "e3" -> blocker)
    validate(board, pawn, "e2", "e3").left.value shouldBe a[DomainError.IllegalMove]
  }

  it should "capture diagonally when an enemy piece is present" in {
    val pawn = Piece(Color.White, PieceType.Pawn)
    val enemy = Piece(Color.Black, PieceType.Rook)
    val board = boardWith("e2" -> pawn, "f3" -> enemy)
    validate(board, pawn, "e2", "f3").value shouldBe ()
  }

  it should "fail a diagonal move without a capture target" in {
    val pawn = Piece(Color.White, PieceType.Pawn)
    val board = boardWith("e2" -> pawn)
    validate(board, pawn, "e2", "f3").left.value shouldBe a[DomainError.IllegalMove]
  }

  it should "fail when moving backward" in {
    val pawn = Piece(Color.White, PieceType.Pawn)
    val board = boardWith("e4" -> pawn)
    validate(board, pawn, "e4", "e3").left.value shouldBe a[DomainError.IllegalMove]
  }

  // ── Pawn (black) ───────────────────────────────────────────────────────────

  "Black pawn" should "move one square forward (toward rank 1) into an empty square" in {
    val pawn = Piece(Color.Black, PieceType.Pawn)
    val board = boardWith("e7" -> pawn)
    validate(board, pawn, "e7", "e6").value shouldBe ()
  }

  it should "move two squares forward from the starting rank when path is clear" in {
    val pawn = Piece(Color.Black, PieceType.Pawn)
    val board = boardWith("e7" -> pawn)
    validate(board, pawn, "e7", "e5").value shouldBe ()
  }

  it should "fail the double move when the intermediate square is occupied" in {
    val pawn = Piece(Color.Black, PieceType.Pawn)
    val blocker = Piece(Color.White, PieceType.Pawn)
    val board = boardWith("e7" -> pawn, "e6" -> blocker)
    validate(board, pawn, "e7", "e5").left.value shouldBe a[DomainError.BlockedPath]
  }

  it should "fail the double move from a non-starting rank" in {
    val pawn = Piece(Color.Black, PieceType.Pawn)
    val board = boardWith("e6" -> pawn)
    validate(board, pawn, "e6", "e4").left.value shouldBe a[DomainError.IllegalMove]
  }

  it should "capture diagonally when an enemy piece is present" in {
    val pawn = Piece(Color.Black, PieceType.Pawn)
    val enemy = Piece(Color.White, PieceType.Rook)
    val board = boardWith("e7" -> pawn, "f6" -> enemy)
    validate(board, pawn, "e7", "f6").value shouldBe ()
  }

  it should "fail a diagonal move without a capture target" in {
    val pawn = Piece(Color.Black, PieceType.Pawn)
    val board = boardWith("e7" -> pawn)
    validate(board, pawn, "e7", "f6").left.value shouldBe a[DomainError.IllegalMove]
  }

  it should "fail when moving backward (toward rank 8)" in {
    val pawn = Piece(Color.Black, PieceType.Pawn)
    val board = boardWith("e5" -> pawn)
    validate(board, pawn, "e5", "e6").left.value shouldBe a[DomainError.IllegalMove]
  }

  // ── MoveValidator.pawnMidSquare ────────────────────────────────────────────

  "MoveValidator.pawnMidSquare" should "throw AssertionError with a descriptive message for out-of-bounds coordinates" in {
    val ex = intercept[AssertionError] {
      MoveValidator.pawnMidSquare(0, 8)
    }
    ex.getMessage should include("Invalid pawn mid-square")
  }
