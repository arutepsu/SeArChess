package chess.notation.fen

import chess.notation.api.ParseFailure
import org.scalatest.EitherValues
import org.scalatest.EitherValues.convertLeftProjectionToValuable
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class FenRegexGrammarSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def parse(input: String): Either[ParseFailure, FenRecord] =
    FenRegexGrammar.parseRecord(input)

  private def syntaxErrorMessage(input: String): String =
    parse(input).left.value match
      case ParseFailure.SyntaxError(msg, _, _) => msg
      case other                               => fail(s"Expected SyntaxError, got $other")

  "FenRegexGrammar.parseRecord" should "return UnexpectedEndOfInput for empty input" in {
    parse("") shouldBe Left(
      ParseFailure.UnexpectedEndOfInput("FEN string is empty")
    )
  }

  it should "parse a valid starting position FEN" in {
    val input = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    val record = parse(input).value
    record.activeColor shouldBe FenColor.White
    record.castling shouldBe FenCastlingAvailability(
      whiteKingSide = true,
      whiteQueenSide = true,
      blackKingSide = true,
      blackQueenSide = true
    )
    record.enPassant shouldBe FenEnPassantTarget.Absent
    record.halfmoveClock shouldBe 0
    record.fullmoveNumber shouldBe 1
    record.ranks should have size 8
    all(record.ranks.map(_.size)) shouldBe 8
  }

  it should "parse a valid FEN with black to move, no castling, and en passant target" in {
    val input = "8/8/8/8/8/8/8/8 b - e3 12 34"

    val record = parse(input).value
    record.activeColor shouldBe FenColor.Black
    record.castling shouldBe FenCastlingAvailability.none
    record.enPassant shouldBe FenEnPassantTarget.Square(file = 4, rank = 2)
    record.halfmoveClock shouldBe 12
    record.fullmoveNumber shouldBe 34
  }

  it should "parse all white piece symbols correctly" in {
    val input = "KQRBNPPP/8/8/8/8/8/8/8 w - - 0 1"

    parse(input).value.ranks.head shouldBe Vector(
      FenSquare.Occupied(FenColor.White, FenPieceSymbol.King),
      FenSquare.Occupied(FenColor.White, FenPieceSymbol.Queen),
      FenSquare.Occupied(FenColor.White, FenPieceSymbol.Rook),
      FenSquare.Occupied(FenColor.White, FenPieceSymbol.Bishop),
      FenSquare.Occupied(FenColor.White, FenPieceSymbol.Knight),
      FenSquare.Occupied(FenColor.White, FenPieceSymbol.Pawn),
      FenSquare.Occupied(FenColor.White, FenPieceSymbol.Pawn),
      FenSquare.Occupied(FenColor.White, FenPieceSymbol.Pawn)
    )
  }

  it should "parse all black piece symbols correctly" in {
    val input = "kqrbnppp/8/8/8/8/8/8/8 w - - 0 1"

    parse(input).value.ranks.head shouldBe Vector(
      FenSquare.Occupied(FenColor.Black, FenPieceSymbol.King),
      FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Queen),
      FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Rook),
      FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Bishop),
      FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Knight),
      FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Pawn),
      FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Pawn),
      FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Pawn)
    )
  }

  it should "parse empty runs from digits correctly" in {
    val input = "12311/8/8/8/8/8/8/8 w - - 0 1"

    parse(input).value.ranks.head shouldBe Vector.fill(8)(FenSquare.Empty)
  }

  it should "parse castling combinations into flags" in {
    parse("8/8/8/8/8/8/8/8 w K - 0 1").value.castling shouldBe
      FenCastlingAvailability(true, false, false, false)

    parse("8/8/8/8/8/8/8/8 w Q - 0 1").value.castling shouldBe
      FenCastlingAvailability(false, true, false, false)

    parse("8/8/8/8/8/8/8/8 w k - 0 1").value.castling shouldBe
      FenCastlingAvailability(false, false, true, false)

    parse("8/8/8/8/8/8/8/8 w q - 0 1").value.castling shouldBe
      FenCastlingAvailability(false, false, false, true)

    parse("8/8/8/8/8/8/8/8 w Kq - 0 1").value.castling shouldBe
      FenCastlingAvailability(true, false, false, true)
  }

  it should "reject FEN with too few fields" in {
    parse("8/8/8/8/8/8/8/8 w - - 0").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "reject FEN with too many fields" in {
    parse("8/8/8/8/8/8/8/8 w - - 0 1 extra").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "reject FEN with double spaces" in {
    parse("8/8/8/8/8/8/8/8  w - - 0 1").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "reject FEN with missing spaces between fields" in {
    parse("8/8/8/8/8/8/8/8w - - 0 1").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "reject invalid active color" in {
    parse("8/8/8/8/8/8/8/8 x - - 0 1").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "reject invalid castling symbol" in {
    parse("8/8/8/8/8/8/8/8 w A - 0 1").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "reject duplicate castling symbols" in {
    val err = parse("8/8/8/8/8/8/8/8 w KK - 0 1").left.value
    err shouldBe a[ParseFailure.SyntaxError]
    syntaxErrorMessage("8/8/8/8/8/8/8/8 w KK - 0 1") should include("contains duplicate symbols")
  }

  it should "reject invalid en passant file" in {
    parse("8/8/8/8/8/8/8/8 w - i3 0 1").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "reject invalid en passant rank" in {
    parse("8/8/8/8/8/8/8/8 w - e9 0 1").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "reject malformed en passant token" in {
    parse("8/8/8/8/8/8/8/8 w - ep 0 1").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "parse halfmove clock zero" in {
    parse("8/8/8/8/8/8/8/8 w - - 0 1").value.halfmoveClock shouldBe 0
  }

  it should "reject non-numeric halfmove clock" in {
    parse("8/8/8/8/8/8/8/8 w - - x 1").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "reject negative halfmove clock" in {
    parse("8/8/8/8/8/8/8/8 w - - -1 1").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "parse fullmove number greater than zero" in {
    parse("8/8/8/8/8/8/8/8 w - - 0 99").value.fullmoveNumber shouldBe 99
  }

  it should "reject fullmove number zero" in {
    parse("8/8/8/8/8/8/8/8 w - - 0 0").left.value shouldBe a[ParseFailure.SyntaxError]
    syntaxErrorMessage("8/8/8/8/8/8/8/8 w - - 0 0") should include("fullmove number must be a positive integer")
  }

  it should "reject non-numeric fullmove number" in {
    parse("8/8/8/8/8/8/8/8 w - - 0 x").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "reject negative fullmove number" in {
    parse("8/8/8/8/8/8/8/8 w - - 0 -1").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "reject piece placement with too few ranks" in {
    parse("8/8/8/8/8/8/8 w - - 0 1").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "reject piece placement with too many ranks" in {
    parse("8/8/8/8/8/8/8/8/8 w - - 0 1").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "reject a rank that expands to 7 squares" in {
    parse("7/8/8/8/8/8/8/8 w - - 0 1").left.value shouldBe a[ParseFailure.SyntaxError]
    syntaxErrorMessage("7/8/8/8/8/8/8/8 w - - 0 1") should include("rank expands to 7 squares; expected 8")
  }

  it should "reject a rank that expands to 9 squares" in {
    parse("81/8/8/8/8/8/8/8 w - - 0 1").left.value shouldBe a[ParseFailure.SyntaxError]
    syntaxErrorMessage("81/8/8/8/8/8/8/8 w - - 0 1") should include("rank expands to 9 squares; expected 8")
  }

  it should "reject illegal piece symbols" in {
    parse("x7/8/8/8/8/8/8/8 w - - 0 1").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "reject rank containing zero as an empty-run digit" in {
    parse("07/8/8/8/8/8/8/8 w - - 0 1").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "reject rank containing digit 9 as an empty-run digit" in {
    parse("9/8/8/8/8/8/8/8 w - - 0 1").left.value shouldBe a[ParseFailure.SyntaxError]
  }

  it should "include line and column information in syntax error messages" in {
    val msg = syntaxErrorMessage("8/8/8/8/8/8/8/8 w KK - 0 1")
    msg should include("[line 1, column")
    msg should include("failed parsing FEN")
  }