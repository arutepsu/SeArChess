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

  private def applied(result: MoveResult): Board = result match
    case MoveResult.Applied(b)              => b
    case MoveResult.PromotionRequired(b, _, _) => fail(s"expected Applied but got PromotionRequired at $b")

  // ── success ────────────────────────────────────────────────────────────────

  "MoveApplier.applyMove" should "move a piece from source to target" in {
    val board    = Board.empty.place(from, piece)
    val newBoard = applied(MoveApplier.applyMove(board, Move(from, to)).value)
    newBoard.pieceAt(to)   shouldBe Some(piece)
    newBoard.pieceAt(from) shouldBe None
  }

  it should "overwrite an occupied target square on a legal capture" in {
    val board    = Board.empty.place(from, piece).place(capTo, enemy)
    val newBoard = applied(MoveApplier.applyMove(board, Move(from, capTo)).value)
    newBoard.pieceAt(capTo) shouldBe Some(piece)
    newBoard.pieceAt(from)  shouldBe None
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

  // ── promotion ──────────────────────────────────────────────────────────────

  it should "return PromotionRequired when a white pawn reaches rank 8" in {
    val a7 = Position.from(0, 6).value
    val a8 = Position.from(0, 7).value
    val board = Board.empty.place(a7, Piece(Color.White, PieceType.Pawn))
    val result = MoveApplier.applyMove(board, Move(a7, a8)).value
    result shouldBe a[MoveResult.PromotionRequired]
    val MoveResult.PromotionRequired(_, sq, col) = result: @unchecked
    sq  shouldBe a8
    col shouldBe Color.White
  }

  it should "return PromotionRequired when a black pawn reaches rank 1" in {
    val b2 = Position.from(1, 1).value
    val b1 = Position.from(1, 0).value
    val board = Board.empty.place(b2, Piece(Color.Black, PieceType.Pawn))
    val result = MoveApplier.applyMove(board, Move(b2, b1)).value
    result shouldBe a[MoveResult.PromotionRequired]
    val MoveResult.PromotionRequired(_, sq, col) = result: @unchecked
    sq  shouldBe b1
    col shouldBe Color.Black
  }

  it should "return Applied for a non-pawn move to the last rank" in {
    val a1 = Position.from(0, 0).value
    val a8 = Position.from(0, 7).value
    val board = Board.empty.place(a1, Piece(Color.White, PieceType.Rook))
    MoveApplier.applyMove(board, Move(a1, a8)).value shouldBe a[MoveResult.Applied]
  }

  it should "still enforce king safety before returning PromotionRequired" in {
    // Diagonal-pin scenario:
    //   White king at a6, White pawn at b7 (about to promote)
    //   Black bishop at c8 — diagonal c8→b7→a6 is blocked by the pawn.
    //   After pawn moves b7→b8, b7 is empty and the bishop checks the king at a6.
    val b7 = Position.from(1, 6).value  // pawn — will move to b8
    val b8 = Position.from(1, 7).value  // promotion square (empty)
    val a6 = Position.from(0, 5).value  // white king
    val c8 = Position.from(2, 7).value  // black bishop — pinning the pawn
    val board = Board.empty
      .place(b7, Piece(Color.White, PieceType.Pawn))
      .place(a6, Piece(Color.White, PieceType.King))
      .place(c8, Piece(Color.Black, PieceType.Bishop))
    MoveApplier.applyMove(board, Move(b7, b8)).left.value shouldBe DomainError.KingInCheck
  }
