package chess.domain.rules

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import chess.domain.error.DomainError
import chess.domain.model.*

class MoveApplierSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val from  = Position.from(0, 0).value
  private val to    = Position.from(1, 0).value
  private val piece = Piece(Color.White, PieceType.Pawn)
  private val enemy = Piece(Color.Black, PieceType.Rook)

  // ── success ────────────────────────────────────────────────────────────────

  "MoveApplier.applyMove" should "move a piece from source to target" in {
    val board  = Board.empty.place(from, piece)
    val result = MoveApplier.applyMove(board, Move(from, to)).value
    result.pieceAt(to)   shouldBe Some(piece)
    result.pieceAt(from) shouldBe None
  }

  it should "overwrite an occupied target square" in {
    val board  = Board.empty.place(from, piece).place(to, enemy)
    val result = MoveApplier.applyMove(board, Move(from, to)).value
    result.pieceAt(to)   shouldBe Some(piece)
    result.pieceAt(from) shouldBe None
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
    error shouldBe DomainError.EmptySourceSquare(from.toString)
  }
