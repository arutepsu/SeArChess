package chess.domain.rules

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import chess.domain.error.DomainError
import chess.domain.model.*

class MoveApplierSpec extends AnyFlatSpec with Matchers with EitherValues:

  // a2→a3: legal single-forward white-pawn move on an otherwise empty board
  private val from    = Position.from(0, 1).value  // a2
  private val to      = Position.from(0, 2).value  // a3
  // b3: used as capture target (a2 pawn captures diagonally)
  private val capTo   = Position.from(1, 2).value  // b3
  private val piece   = Piece(Color.White, PieceType.Pawn)
  private val enemy   = Piece(Color.Black, PieceType.Rook)

  // ── success ────────────────────────────────────────────────────────────────

  "MoveApplier.applyMove" should "move a piece from source to target" in {
    val board  = Board.empty.place(from, piece)
    val result = MoveApplier.applyMove(board, Move(from, to)).value
    result.pieceAt(to)   shouldBe Some(piece)
    result.pieceAt(from) shouldBe None
  }

  it should "overwrite an occupied target square on a legal capture" in {
    val board  = Board.empty.place(from, piece).place(capTo, enemy)
    val result = MoveApplier.applyMove(board, Move(from, capTo)).value
    result.pieceAt(capTo) shouldBe Some(piece)
    result.pieceAt(from)  shouldBe None
  }

  it should "leave the original board unchanged (immutability)" in {
    val original = Board.empty.place(from, piece)
    val _        = MoveApplier.applyMove(original, Move(from, to))
    original.pieceAt(from) shouldBe Some(piece)
  }

  // ── failure ────────────────────────────────────────────────────────────────

  it should "fail with EmptySourceSquare when the source square is empty" in {
    val result = MoveApplier.applyMove(Board.empty, Move(from, to))
    result.left.value shouldBe a[DomainError.EmptySourceSquare]
  }

  it should "include the source position label in the EmptySourceSquare error" in {
    val error = MoveApplier.applyMove(Board.empty, Move(from, to)).left.value
    error shouldBe DomainError.EmptySourceSquare(from)
  }
