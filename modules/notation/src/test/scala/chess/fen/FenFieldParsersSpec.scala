package chess.notation.fen

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import chess.notation.api.ParseFailure

class FenFieldParsersSpec extends AnyFlatSpec with Matchers with EitherValues:

  // ── parsePiecePlacement ──────────────────────────────────────────────────────

  "FenFieldParsers.parsePiecePlacement" should "parse the initial-position piece placement" in {
    val result = FenFieldParsers.parsePiecePlacement("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")
    val ranks  = result.value
    ranks should have size 8
    ranks.foreach(_ should have size 8)
  }

  it should "decode white pieces from uppercase letters" in {
    // Rank with all white piece types + empty squares
    val ranks = FenFieldParsers.parsePiecePlacement("KQRBNP2/8/8/8/8/8/8/8").value
    val rank8 = ranks(0)
    rank8(0) shouldBe FenSquare.Occupied(FenColor.White, FenPieceSymbol.King)
    rank8(1) shouldBe FenSquare.Occupied(FenColor.White, FenPieceSymbol.Queen)
    rank8(2) shouldBe FenSquare.Occupied(FenColor.White, FenPieceSymbol.Rook)
    rank8(3) shouldBe FenSquare.Occupied(FenColor.White, FenPieceSymbol.Bishop)
    rank8(4) shouldBe FenSquare.Occupied(FenColor.White, FenPieceSymbol.Knight)
    rank8(5) shouldBe FenSquare.Occupied(FenColor.White, FenPieceSymbol.Pawn)
    rank8(6) shouldBe FenSquare.Empty
    rank8(7) shouldBe FenSquare.Empty
  }

  it should "decode black pieces from lowercase letters" in {
    val ranks = FenFieldParsers.parsePiecePlacement("kqrbnp11/8/8/8/8/8/8/8").value
    val rank8 = ranks(0)
    rank8(0) shouldBe FenSquare.Occupied(FenColor.Black, FenPieceSymbol.King)
    rank8(1) shouldBe FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Queen)
    rank8(2) shouldBe FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Rook)
    rank8(3) shouldBe FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Bishop)
    rank8(4) shouldBe FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Knight)
    rank8(5) shouldBe FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Pawn)
  }

  it should "expand all digit values 1–8 as empty squares" in {
    // Each rank uses a single digit to fill it
    val ranks = FenFieldParsers.parsePiecePlacement("8/7P/6PP/5PPP/4PPPP/3PPPPP/2PPPPPP/1PPPPPPP").value
    ranks(0).forall(_ == FenSquare.Empty) shouldBe true
    ranks(1).count(_ == FenSquare.Empty)  shouldBe 7
    ranks(7).count(_ == FenSquare.Empty)  shouldBe 1
  }

  it should "return StructuralError for too few ranks (7)" in {
    val result = FenFieldParsers.parsePiecePlacement("8/8/8/8/8/8/8")
    result.left.value shouldBe a[ParseFailure.StructuralError]
  }

  it should "return StructuralError for too many ranks (9)" in {
    val result = FenFieldParsers.parsePiecePlacement("8/8/8/8/8/8/8/8/8")
    result.left.value shouldBe a[ParseFailure.StructuralError]
  }

  it should "return SyntaxError for an illegal piece symbol in a rank" in {
    val result = FenFieldParsers.parsePiecePlacement("8/8/8/8/8/8/8/7X")
    result.left.value shouldBe a[ParseFailure.SyntaxError]
    result.left.value.message should include("X")
  }

  it should "return StructuralError when a rank expands to fewer than 8 squares" in {
    // "7" = 7 empty squares, one short
    val result = FenFieldParsers.parsePiecePlacement("8/8/8/8/8/8/8/7")
    result.left.value shouldBe a[ParseFailure.StructuralError]
  }

  it should "return StructuralError when a rank expands to more than 8 squares" in {
    // "81" = 8 empty + 1 empty = 9 squares
    val result = FenFieldParsers.parsePiecePlacement("8/8/8/8/8/8/8/81")
    result.left.value shouldBe a[ParseFailure.StructuralError]
  }

  it should "report the first failing rank and stop" in {
    // Two bad ranks — only the first error should be reported
    val result = FenFieldParsers.parsePiecePlacement("7X/7Y/8/8/8/8/8/8")
    result.left.value.message should include("X")
  }

  // ── parseActiveColor ─────────────────────────────────────────────────────────

  "FenFieldParsers.parseActiveColor" should "parse 'w' as White" in {
    FenFieldParsers.parseActiveColor("w").value shouldBe FenColor.White
  }

  it should "parse 'b' as Black" in {
    FenFieldParsers.parseActiveColor("b").value shouldBe FenColor.Black
  }

  it should "return SyntaxError for any other token" in {
    val result = FenFieldParsers.parseActiveColor("W")
    result.left.value shouldBe a[ParseFailure.SyntaxError]
    result.left.value.message should include("W")
  }

  it should "reject empty string" in {
    FenFieldParsers.parseActiveColor("").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  // ── parseCastling ────────────────────────────────────────────────────────────

  "FenFieldParsers.parseCastling" should "parse '-' as no castling available" in {
    val result = FenFieldParsers.parseCastling("-").value
    result shouldBe FenCastlingAvailability.none
    result.whiteKingSide  shouldBe false
    result.whiteQueenSide shouldBe false
    result.blackKingSide  shouldBe false
    result.blackQueenSide shouldBe false
  }

  it should "parse 'KQkq' with all four rights enabled" in {
    val result = FenFieldParsers.parseCastling("KQkq").value
    result.whiteKingSide  shouldBe true
    result.whiteQueenSide shouldBe true
    result.blackKingSide  shouldBe true
    result.blackQueenSide shouldBe true
  }

  it should "parse partial castling 'Kq'" in {
    val result = FenFieldParsers.parseCastling("Kq").value
    result.whiteKingSide  shouldBe true
    result.whiteQueenSide shouldBe false
    result.blackKingSide  shouldBe false
    result.blackQueenSide shouldBe true
  }

  it should "parse 'Qq' (white queen-side and black queen-side only)" in {
    val result = FenFieldParsers.parseCastling("Qq").value
    result.whiteKingSide  shouldBe false
    result.whiteQueenSide shouldBe true
    result.blackKingSide  shouldBe false
    result.blackQueenSide shouldBe true
  }

  it should "return SyntaxError for an invalid castling character" in {
    val result = FenFieldParsers.parseCastling("X")
    result.left.value shouldBe a[ParseFailure.SyntaxError]
    result.left.value.message should include("X")
  }

  it should "return SyntaxError for empty castling field" in {
    FenFieldParsers.parseCastling("").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return StructuralError for a duplicate castling symbol" in {
    FenFieldParsers.parseCastling("KK").left.value shouldBe a[ParseFailure.StructuralError]
  }

  // ── parseEnPassant ───────────────────────────────────────────────────────────

  "FenFieldParsers.parseEnPassant" should "parse '-' as Absent" in {
    FenFieldParsers.parseEnPassant("-").value shouldBe FenEnPassantTarget.Absent
  }

  it should "parse a valid en passant square 'e3'" in {
    val result = FenFieldParsers.parseEnPassant("e3").value
    result shouldBe FenEnPassantTarget.Square(4, 2)
  }

  it should "parse 'a1' as the minimum valid square" in {
    FenFieldParsers.parseEnPassant("a1").value shouldBe FenEnPassantTarget.Square(0, 0)
  }

  it should "parse 'h8' as the maximum valid square" in {
    FenFieldParsers.parseEnPassant("h8").value shouldBe FenEnPassantTarget.Square(7, 7)
  }

  it should "return SyntaxError for an invalid file letter" in {
    FenFieldParsers.parseEnPassant("z3").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError for an invalid rank digit" in {
    FenFieldParsers.parseEnPassant("e9").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError for a too-long token" in {
    FenFieldParsers.parseEnPassant("e33").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError for a single-character token" in {
    FenFieldParsers.parseEnPassant("e").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  // ── parseHalfmoveClock ───────────────────────────────────────────────────────

  "FenFieldParsers.parseHalfmoveClock" should "parse '0'" in {
    FenFieldParsers.parseHalfmoveClock("0").value shouldBe 0
  }

  it should "parse '50'" in {
    FenFieldParsers.parseHalfmoveClock("50").value shouldBe 50
  }

  it should "return SyntaxError for a non-integer value" in {
    FenFieldParsers.parseHalfmoveClock("abc").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError for a negative value" in {
    val result = FenFieldParsers.parseHalfmoveClock("-1")
    result.left.value shouldBe a[ParseFailure.SyntaxError]
    result.left.value.message should include("-1")
  }

  it should "return SyntaxError for a decimal value" in {
    FenFieldParsers.parseHalfmoveClock("1.5").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  // ── parseFullmoveNumber ──────────────────────────────────────────────────────

  "FenFieldParsers.parseFullmoveNumber" should "parse '1'" in {
    FenFieldParsers.parseFullmoveNumber("1").value shouldBe 1
  }

  it should "parse '45'" in {
    FenFieldParsers.parseFullmoveNumber("45").value shouldBe 45
  }

  it should "return SyntaxError for '0' (not positive)" in {
    FenFieldParsers.parseFullmoveNumber("0").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError for a negative value" in {
    FenFieldParsers.parseFullmoveNumber("-1").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError for a non-integer value" in {
    FenFieldParsers.parseFullmoveNumber("one").left.value shouldBe a[ParseFailure.SyntaxError]
  }
