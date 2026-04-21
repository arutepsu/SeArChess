package chess.domain.rules.application

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import chess.domain.error.DomainError
import chess.domain.model.*

class PromotionApplierSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val square = Position.from(0, 7).value // a8 — White promotion rank (7)
  private val color = Color.White
  private val pawnBoard = Board.empty.place(square, Piece(color, PieceType.Pawn))

  // ── success ────────────────────────────────────────────────────────────────

  "PromotionApplier.applyPromotion" should "promote to Queen" in {
    val result = PromotionApplier.applyPromotion(pawnBoard, square, color, PieceType.Queen).value
    result.pieceAt(square) shouldBe Some(Piece(Color.White, PieceType.Queen))
  }

  it should "promote to Rook" in {
    val result = PromotionApplier.applyPromotion(pawnBoard, square, color, PieceType.Rook).value
    result.pieceAt(square) shouldBe Some(Piece(Color.White, PieceType.Rook))
  }

  it should "promote to Bishop" in {
    val result = PromotionApplier.applyPromotion(pawnBoard, square, color, PieceType.Bishop).value
    result.pieceAt(square) shouldBe Some(Piece(Color.White, PieceType.Bishop))
  }

  it should "promote to Knight" in {
    val result = PromotionApplier.applyPromotion(pawnBoard, square, color, PieceType.Knight).value
    result.pieceAt(square) shouldBe Some(Piece(Color.White, PieceType.Knight))
  }

  it should "preserve the correct color for a Black promotion" in {
    val blackSquare = Position.from(0, 0).value // a1 — Black promotion rank (0)
    val board = Board.empty.place(blackSquare, Piece(Color.Black, PieceType.Pawn))
    val result =
      PromotionApplier.applyPromotion(board, blackSquare, Color.Black, PieceType.Queen).value
    result.pieceAt(blackSquare) shouldBe Some(Piece(Color.Black, PieceType.Queen))
  }

  it should "leave the rest of the board unchanged" in {
    val other = Position.from(4, 4).value // e5
    val board = pawnBoard.place(other, Piece(Color.Black, PieceType.King))
    val result = PromotionApplier.applyPromotion(board, square, color, PieceType.Queen).value
    result.pieceAt(other) shouldBe Some(Piece(Color.Black, PieceType.King))
  }

  // ── InvalidPromotionPiece ─────────────────────────────────────────────────

  it should "reject King as a promotion target" in {
    PromotionApplier.applyPromotion(pawnBoard, square, color, PieceType.King).left.value shouldBe
      DomainError.InvalidPromotionPiece
  }

  it should "reject Pawn as a promotion target" in {
    PromotionApplier.applyPromotion(pawnBoard, square, color, PieceType.Pawn).left.value shouldBe
      DomainError.InvalidPromotionPiece
  }

  // ── InvalidPromotionState ─────────────────────────────────────────────────

  it should "fail with InvalidPromotionState when no piece is at the promotion square" in {
    PromotionApplier.applyPromotion(Board.empty, square, color, PieceType.Queen).left.value shouldBe
      DomainError.InvalidPromotionState
  }

  it should "fail with InvalidPromotionState when the piece at the square is not a Pawn" in {
    val board = Board.empty.place(square, Piece(Color.White, PieceType.Rook))
    PromotionApplier.applyPromotion(board, square, color, PieceType.Queen).left.value shouldBe
      DomainError.InvalidPromotionState
  }

  it should "fail with InvalidPromotionState when the pawn has the wrong color" in {
    val board = Board.empty.place(square, Piece(Color.Black, PieceType.Pawn))
    PromotionApplier.applyPromotion(board, square, Color.White, PieceType.Queen).left.value shouldBe
      DomainError.InvalidPromotionState
  }

  it should "fail with InvalidPromotionState when the square is not on the correct promotion rank" in {
    val e4 = Position.from(4, 3).value // e4 — not a promotion rank
    val board = Board.empty.place(e4, Piece(Color.White, PieceType.Pawn))
    PromotionApplier.applyPromotion(board, e4, Color.White, PieceType.Queen).left.value shouldBe
      DomainError.InvalidPromotionState
  }
