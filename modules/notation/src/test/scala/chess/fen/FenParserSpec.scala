package chess.notation.fen

import chess.notation.api.{FenData, NotationFormat, ParsedNotation, ParseFailure}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.Assertions.fail
import org.scalatest.EitherValues.convertLeftProjectionToValuable

/** Tests the public [[FenParser]] contract.
 *
 *  Verifies:
 *  - format declaration
 *  - success cases preserve the raw input string
 *  - parser-local [[FenRecord]] is converted correctly to shared [[FenData]]
 *  - failures are surfaced through the public [[ParseFailure]] contract
 *
 *  No semantic chess-legality checks are tested here because the parser does
 *  not perform them.
 */
final class FenParserSpec extends AnyFlatSpec with Matchers with EitherValues:

  // ── Constants ──────────────────────────────────────────────────────────────

  private val InitialFen =
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  private val AfterE4Fen =
    "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"

  private val MidgameFen =
    "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4"

  private val NoCastlingFen =
    "8/8/8/8/8/8/8/4K2R w - - 0 1"

  // ── Helpers ────────────────────────────────────────────────────────────────

  private def parsedFen(input: String): ParsedNotation.ParsedFen =
    FenParser.parse(input).value match
      case fen: ParsedNotation.ParsedFen => fen
      case other                         => fail(s"Expected ParsedFen, got $other")

  private def syntaxErrorMessage(input: String): String =
    FenParser.parse(input).left.value match
      case ParseFailure.SyntaxError(message, _, _) => message
      case other                                   => fail(s"Expected SyntaxError, got $other")

  // ── Format declaration ─────────────────────────────────────────────────────

  "FenParser" should "declare its format as FEN" in {
    FenParser.format shouldBe NotationFormat.FEN
  }

  // ── Success cases: public parse contract ───────────────────────────────────

  it should "return Right(ParsedFen) for the standard initial position" in {
    FenParser.parse(InitialFen).value shouldBe a[ParsedNotation.ParsedFen]
  }

  it should "preserve the exact raw input string in the result" in {
    parsedFen(InitialFen).raw shouldBe InitialFen
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
    parsedFen(AfterE4Fen).data.activeColor shouldBe FenData.ActiveColor.Black
  }

  // ── Success cases: FenRecord delegation ────────────────────────────────────

  it should "return a FenRecord with correct fields for the initial position" in {
    val record = FenParser.parseRecord(InitialFen).value

    record.activeColor            shouldBe FenColor.White
    record.castling.whiteKingSide shouldBe true
    record.castling.whiteQueenSide shouldBe true
    record.castling.blackKingSide shouldBe true
    record.castling.blackQueenSide shouldBe true
    record.enPassant              shouldBe FenEnPassantTarget.Absent
    record.halfmoveClock          shouldBe 0
    record.fullmoveNumber         shouldBe 1
    record.ranks should have size 8
    all(record.ranks.map(_.size)) shouldBe 8
  }

  it should "return a FenRecord with en passant square after 1. e4" in {
    val record = FenParser.parseRecord(AfterE4Fen).value
    record.activeColor shouldBe FenColor.Black
    record.enPassant shouldBe FenEnPassantTarget.Square(4, 2)
  }

  // ── Success cases: FenRecord -> FenData conversion ─────────────────────────

  it should "convert active color from FenRecord to FenData" in {
    parsedFen(InitialFen).data.activeColor shouldBe FenData.ActiveColor.White
    parsedFen(AfterE4Fen).data.activeColor shouldBe FenData.ActiveColor.Black
  }

  it should "convert castling rights from FenRecord to FenData" in {
    parsedFen(InitialFen).data.castling shouldBe
      FenData.CastlingAvailability(
        whiteKingSide = true,
        whiteQueenSide = true,
        blackKingSide = true,
        blackQueenSide = true
      )

    parsedFen(NoCastlingFen).data.castling shouldBe
      FenData.CastlingAvailability(
        whiteKingSide = false,
        whiteQueenSide = false,
        blackKingSide = false,
        blackQueenSide = false
      )
  }

  it should "convert en passant target from FenRecord to FenData" in {
    parsedFen(InitialFen).data.enPassant shouldBe FenData.EnPassantTarget.Absent
    parsedFen(AfterE4Fen).data.enPassant shouldBe FenData.EnPassantTarget.Square(4, 2)
  }

  it should "convert halfmove and fullmove numbers from FenRecord to FenData" in {
    val data = parsedFen(MidgameFen).data
    data.halfmoveClock shouldBe 4
    data.fullmoveNumber shouldBe 4
  }

  it should "convert empty squares correctly into FenData" in {
    val data = parsedFen("8/8/8/8/8/8/8/8 w - - 0 1").data
    data.ranks should have size 8
    all(data.ranks.map(_.size)) shouldBe 8
    all(data.ranks.flatten) shouldBe FenData.Square.Empty
  }

  it should "convert occupied squares correctly into FenData" in {
    val data = parsedFen("K7/8/8/8/8/8/8/7k w - - 0 1").data

    data.ranks.head.head shouldBe
      FenData.Square.Occupied(FenData.ActiveColor.White, FenData.PieceSymbol.King)

    data.ranks.last.last shouldBe
      FenData.Square.Occupied(FenData.ActiveColor.Black, FenData.PieceSymbol.King)
  }

  it should "convert all piece symbols correctly into FenData" in {
    val data = parsedFen("KQRBNP2/8/8/8/8/8/8/kqrbnp2 w - - 0 1").data

    data.ranks.head.take(6) shouldBe Vector(
      FenData.Square.Occupied(FenData.ActiveColor.White, FenData.PieceSymbol.King),
      FenData.Square.Occupied(FenData.ActiveColor.White, FenData.PieceSymbol.Queen),
      FenData.Square.Occupied(FenData.ActiveColor.White, FenData.PieceSymbol.Rook),
      FenData.Square.Occupied(FenData.ActiveColor.White, FenData.PieceSymbol.Bishop),
      FenData.Square.Occupied(FenData.ActiveColor.White, FenData.PieceSymbol.Knight),
      FenData.Square.Occupied(FenData.ActiveColor.White, FenData.PieceSymbol.Pawn)
    )

    data.ranks.last.take(6) shouldBe Vector(
      FenData.Square.Occupied(FenData.ActiveColor.Black, FenData.PieceSymbol.King),
      FenData.Square.Occupied(FenData.ActiveColor.Black, FenData.PieceSymbol.Queen),
      FenData.Square.Occupied(FenData.ActiveColor.Black, FenData.PieceSymbol.Rook),
      FenData.Square.Occupied(FenData.ActiveColor.Black, FenData.PieceSymbol.Bishop),
      FenData.Square.Occupied(FenData.ActiveColor.Black, FenData.PieceSymbol.Knight),
      FenData.Square.Occupied(FenData.ActiveColor.Black, FenData.PieceSymbol.Pawn)
    )
  }

  // ── Empty input ────────────────────────────────────────────────────────────

  it should "return UnexpectedEndOfInput for empty input" in {
    FenParser.parse("").left.value shouldBe
      ParseFailure.UnexpectedEndOfInput("FEN string is empty")
  }

  // ── Public failure contract under combinator parsing ───────────────────────

  it should "return SyntaxError for too few fields" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError for too many fields" in {
    FenParser.parse(s"$InitialFen extra")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError for too few ranks (7)" in {
    FenParser.parse("8/8/8/8/8/8/8 w - - 0 1")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError for too many ranks (9)" in {
    FenParser.parse("8/8/8/8/8/8/8/8/8 w - - 0 1")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError for an illegal piece symbol in a rank" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNX w KQkq - 0 1")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError when a rank expands to fewer than 8 squares" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/7 w KQkq - 0 1")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError when a rank expands to more than 8 squares" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/81 w KQkq - 0 1")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError for an invalid active color token" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR x KQkq - 0 1")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError for an invalid castling symbol" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w X - 0 1")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError for a duplicate castling symbol" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KK - 0 1")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

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

  it should "return SyntaxError for a non-integer halfmove clock" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - abc 1")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "return SyntaxError for a negative halfmove clock" in {
    FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - -1 1")
      .left.value shouldBe a[ParseFailure.SyntaxError]
  }

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

  // ── Failure message quality ────────────────────────────────────────────────

  it should "include the duplicate-castling detail in the syntax error message" in {
    syntaxErrorMessage("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KK - 0 1") should include(
      "contains duplicate symbols"
    )
  }

  it should "include the rank-expansion detail in the syntax error message" in {
    syntaxErrorMessage("7/8/8/8/8/8/8/8 w - - 0 1") should include(
      "rank expands to 7 squares; expected 8"
    )
  }

  it should "include line and column information in syntax error messages" in {
    val msg = syntaxErrorMessage("8/8/8/8/8/8/8/8 w KK - 0 1")
    msg should include("[line 1, column")
    msg should include("failed parsing FEN")
  }

  // ── Short-circuit behaviour ────────────────────────────────────────────────

  it should "fail at piece placement before reaching later fields" in {
    val result = FenParser.parse("7/8/8/8/8/8/8/8 w KQkq - 0 1")
    result.left.value shouldBe a[ParseFailure.SyntaxError]
    result.left.value.message should include("rank expands to 7")
  }