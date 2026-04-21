package chess.domain.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues

class BoardSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val pos = Position.from(0, 0).value
  private val pos2 = Position.from(1, 0).value
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
    val _ = original.place(pos, piece)
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

  // ── Board.initial ──────────────────────────────────────────────────────────

  private def sq(algebraic: String) = Position.fromAlgebraic(algebraic).value

  "Board.initial" should "contain exactly 32 pieces" in {
    (0 to 7)
      .flatMap(f => (0 to 7).flatMap(r => Board.initial.pieceAt(Position.from(f, r).value)))
      .size shouldBe 32
  }

  it should "place the white king on e1" in {
    Board.initial.pieceAt(sq("e1")) shouldBe Some(Piece(Color.White, PieceType.King))
  }

  it should "place the black king on e8" in {
    Board.initial.pieceAt(sq("e8")) shouldBe Some(Piece(Color.Black, PieceType.King))
  }

  it should "place the white queen on d1" in {
    Board.initial.pieceAt(sq("d1")) shouldBe Some(Piece(Color.White, PieceType.Queen))
  }

  it should "place the black queen on d8" in {
    Board.initial.pieceAt(sq("d8")) shouldBe Some(Piece(Color.Black, PieceType.Queen))
  }

  it should "place white pawns on a2 through h2" in {
    ('a' to 'h').foreach { f =>
      Board.initial.pieceAt(sq(s"${f}2")) shouldBe Some(Piece(Color.White, PieceType.Pawn))
    }
  }

  it should "place black pawns on a7 through h7" in {
    ('a' to 'h').foreach { f =>
      Board.initial.pieceAt(sq(s"${f}7")) shouldBe Some(Piece(Color.Black, PieceType.Pawn))
    }
  }

  it should "place white rooks on a1 and h1" in {
    Board.initial.pieceAt(sq("a1")) shouldBe Some(Piece(Color.White, PieceType.Rook))
    Board.initial.pieceAt(sq("h1")) shouldBe Some(Piece(Color.White, PieceType.Rook))
  }

  it should "place black rooks on a8 and h8" in {
    Board.initial.pieceAt(sq("a8")) shouldBe Some(Piece(Color.Black, PieceType.Rook))
    Board.initial.pieceAt(sq("h8")) shouldBe Some(Piece(Color.Black, PieceType.Rook))
  }

  it should "place white knights on b1 and g1" in {
    Board.initial.pieceAt(sq("b1")) shouldBe Some(Piece(Color.White, PieceType.Knight))
    Board.initial.pieceAt(sq("g1")) shouldBe Some(Piece(Color.White, PieceType.Knight))
  }

  it should "place black knights on b8 and g8" in {
    Board.initial.pieceAt(sq("b8")) shouldBe Some(Piece(Color.Black, PieceType.Knight))
    Board.initial.pieceAt(sq("g8")) shouldBe Some(Piece(Color.Black, PieceType.Knight))
  }

  it should "place white bishops on c1 and f1" in {
    Board.initial.pieceAt(sq("c1")) shouldBe Some(Piece(Color.White, PieceType.Bishop))
    Board.initial.pieceAt(sq("f1")) shouldBe Some(Piece(Color.White, PieceType.Bishop))
  }

  it should "place black bishops on c8 and f8" in {
    Board.initial.pieceAt(sq("c8")) shouldBe Some(Piece(Color.Black, PieceType.Bishop))
    Board.initial.pieceAt(sq("f8")) shouldBe Some(Piece(Color.Black, PieceType.Bishop))
  }

  // ── Board.constPos ─────────────────────────────────────────────────────────

  "Board.constPos" should "throw AssertionError with a descriptive message for out-of-bounds coordinates" in {
    val ex = intercept[AssertionError] {
      Board.constPos(8, 0)
    }
    ex.getMessage should include("Invalid board constant: file=8 rank=0")
  }
