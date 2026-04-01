package chess.notation.fen

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import chess.notation.api.{NotationFormat, ParsedNotation, ParseFailure}

/** Tests the public [[FenParser]] contract.
 *
 *  Verifies:
 *  - format declaration
 *  - success cases preserve the raw input string
 *  - every category of failure produces the correct [[ParseFailure]] subtype
 *
 *  No semantic chess-legality checks are tested here because the parser does
 *  not perform them.
 */
class FenParserSpec extends AnyFlatSpec with Matchers with EitherValues:

  // ── Constants ────────────────────────────────────────────────────────────────

  private val InitialFen =
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  private val AfterE4Fen =
    "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"

  private val MidgameFen =
    "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4"

  private val NoCastlingFen =
    "8/8/8/8/8/8/8/4K2R w - - 0 1"

  // ── Format declaration ───────────────────────────────────────────────────────

  "FenParser" should "declare its format as FEN" in {
    FenParser.format shouldBe NotationFormat.FEN
  }

  // ── Success cases ────────────────────────────────────────────────────────────

  it should "return Right(ParsedFen) for the standard initial position" in {
    val result = FenParser.parse(InitialFen)
    result.value shouldBe ParsedNotation.ParsedFen(InitialFen)
  }

  it should "preserve the exact raw input string in the result" in {
    val result = FenParser.parse(InitialFen)
    result.value.raw shouldBe InitialFen
  }

  it should "accept a FEN with an en passant square" in {
    FenParser.parse(AfterE4Fen).value shouldBe a[ParsedNotation.ParsedFen]
  }

  it should "accept a mid-game FEN with partial castling rights" in {
    FenParser.parse(MidgameFen).value shouldBe a[ParsedNotation.ParsedFen]
  }

  it should "accept a FEN with no castling rights ('-')" in {
    FenParser.parse(NoCastlingFen).value shouldBe a[ParsedNotation.ParsedFen]
  }

  it should "accept black as the active color" in {
    FenParser.parse(AfterE4Fen).value shouldBe a[ParsedNotation.ParsedFen]
  }

  // ── parseRecord: success ─────────────────────────────────────────────────────

  it should "return a FenRecord with correct fields for the initial position" in {
    val record = FenParser.parseRecord(InitialFen).value
    record.activeColor            shouldBe FenColor.White
    record.castling.whiteKingSide shouldBe true
    record.castling.blackKingSide shouldBe true
    record.enPassant              shouldBe FenEnPassantTarget.Absent
    record.halfmoveClock          shouldBe 0
    record.fullmoveNumber         shouldBe 1
    record.ranks                  should have size 8
  }

  it should "return a FenRecord with en passant square after 1. e4" in {
    val record = FenParser.parseRecord(AfterE4Fen).value
    record.activeColor shouldBe FenColor.Black
    record.enPassant   shouldBe FenEnPassantTarget.Square(4, 2)
  }

  // ── Empty input ──────────────────────────────────────────────────────────────

  it should "return UnexpectedEndOfInput for empty input" in {
    FenParser.parse("").left.value shouldBe a[ParseFailure.UnexpectedEndOfInput]
  }

  // ── Field-count failures ─────────────────────────────────────────────────────

  it should "return StructuralError for too few fields" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0")
      .left.value shouldBe a[ParseFailure.StructuralError]
  }

  it should "return StructuralError for too many fields" in {
    FenParser.parse(s"$InitialFen extra")
      .left.value shouldBe a[ParseFailure.StructuralError]
  }

  // ── Piece placement failures ─────────────────────────────────────────────────

  it should "return StructuralError for too few ranks (7)" in {
    FenParser.parse("8/8/8/8/8/8/8 w - - 0 1")
      .left.value shouldBe a[ParseFailure.StructuralError]
  }

  it should "return StructuralError for too many ranks (9)" in {
    FenParser.parse("8/8/8/8/8/8/8/8/8 w - - 0 1")
      .left.value shouldBe a[ParseFailure.StructuralError]
  }

  it should "return SyntaxError for an illegal piece symbol in a rank" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNX w KQkq - 0 1")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return StructuralError when a rank expands to fewer than 8 squares" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/7 w KQkq - 0 1")
      .left.value shouldBe a[ParseFailure.StructuralError]
  }

  it should "return StructuralError when a rank expands to more than 8 squares" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/81 w KQkq - 0 1")
      .left.value shouldBe a[ParseFailure.StructuralError]
  }

  // ── Active color failures ────────────────────────────────────────────────────

  it should "return SyntaxError for an invalid active color token" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR x KQkq - 0 1")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  // ── Castling failures ────────────────────────────────────────────────────────

  it should "return SyntaxError for an invalid castling symbol" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w X - 0 1")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return StructuralError for a duplicate castling symbol" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KK - 0 1")
      .left.value shouldBe a[ParseFailure.StructuralError]
  }

  // ── En passant failures ──────────────────────────────────────────────────────

  it should "return SyntaxError for a malformed en passant square (bad file)" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq z3 0 1")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError for a malformed en passant square (bad rank)" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq e9 0 1")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError for a malformed en passant square (too long)" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq e33 0 1")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  // ── Halfmove clock failures ──────────────────────────────────────────────────

  it should "return SyntaxError for a non-integer halfmove clock" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - abc 1")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError for a negative halfmove clock" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - -1 1")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  // ── Fullmove number failures ─────────────────────────────────────────────────

  it should "return SyntaxError for a non-integer fullmove number" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 one")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError for fullmove number 0 (not positive)" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 0")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError for a negative fullmove number" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 -1")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  // ── Short-circuit behaviour ──────────────────────────────────────────────────

  it should "fail at piece placement before reaching later fields" in {
    // First rank has only 7 squares — all later fields are deliberately valid.
    // The returned failure must come from piece placement, not a later field.
    val result = FenParser.parse("7/8/8/8/8/8/8/8 w KQkq - 0 1")
    result.left.value shouldBe a[ParseFailure.StructuralError]
    result.left.value.message should include("7")
  }
