package chess.domain.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues

class BoardSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val pos   = Position.from(0, 0).value
  private val pos2  = Position.from(1, 0).value
  private val piece = Piece(Color.White, PieceType.Pawn)
  private val other = Piece(Color.Black, PieceType.Rook)

  // ── Board.empty ────────────────────────────────────────────────────────────

  "Board.empty" should "have no pieces" in {
    Board.empty.pieceAt(pos) shouldBe None
  }

  // ── Board.place ────────────────────────────────────────────────────────────

  "Board.place" should "put a piece at the given position" in {
    Board.empty.place(pos, piece).pieceAt(pos) shouldBe Some(piece)
  }

  it should "not affect other squares" in {
    Board.empty.place(pos, piece).pieceAt(pos2) shouldBe None
  }

  it should "overwrite an existing piece" in {
    Board.empty.place(pos, piece).place(pos, other).pieceAt(pos) shouldBe Some(other)
  }

  it should "leave the original board unchanged (immutability)" in {
    val original = Board.empty
    val _        = original.place(pos, piece)
    original.pieceAt(pos) shouldBe None
  }

  // ── Board.remove ───────────────────────────────────────────────────────────

  "Board.remove" should "clear the piece at a position" in {
    Board.empty.place(pos, piece).remove(pos).pieceAt(pos) shouldBe None
  }

  it should "be a no-op on an already empty square" in {
    Board.empty.remove(pos).pieceAt(pos) shouldBe None
  }

  it should "not affect other squares" in {
    Board.empty.place(pos, piece).place(pos2, other).remove(pos).pieceAt(pos2) shouldBe Some(other)
  }

  // ── Board.pieceAt ──────────────────────────────────────────────────────────

  "Board.pieceAt" should "return None on an empty board" in {
    Board.empty.pieceAt(pos) shouldBe None
  }

  it should "return Some(piece) after placement" in {
    Board.empty.place(pos, piece).pieceAt(pos) shouldBe Some(piece)
  }
