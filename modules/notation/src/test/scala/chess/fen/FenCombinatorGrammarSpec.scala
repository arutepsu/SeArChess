package chess.notation.fen

import chess.notation.api.ParseFailure
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class FenCombinatorGrammarSpec extends AnyWordSpec with Matchers:

  private def parse(input: String): Either[ParseFailure, FenRecord] =
    FenCombinatorGrammar.parseRecord(input)

  "FenCombinatorGrammar.parseRecord" should {

    "return UnexpectedEndOfInput for empty input" in {
      parse("") shouldBe Left(
        ParseFailure.UnexpectedEndOfInput("FEN string is empty")
      )
    }

    "parse a valid starting position FEN" in {
      val input = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

      val result = parse(input)

      result.isRight shouldBe true

      val record = result.toOption.get
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

    "parse a valid FEN with black to move, no castling, and en passant target" in {
      val input = "8/8/8/8/8/8/8/8 b - e3 12 34"

      val result = parse(input)

      result.isRight shouldBe true

      val record = result.toOption.get
      record.activeColor shouldBe FenColor.Black
      record.castling shouldBe FenCastlingAvailability.none
      record.enPassant shouldBe FenEnPassantTarget.Square(file = 4, rank = 2)
      record.halfmoveClock shouldBe 12
      record.fullmoveNumber shouldBe 34
    }

    "parse all white piece symbols correctly" in {
      val input = "KQRBNPPP/8/8/8/8/8/8/8 w - - 0 1"

      val result = parse(input)

      result.isRight shouldBe true

      val firstRank = result.toOption.get.ranks.head
      firstRank shouldBe Vector(
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

    "parse all black piece symbols correctly" in {
      val input = "kqrbnppp/8/8/8/8/8/8/8 w - - 0 1"

      val result = parse(input)

      result.isRight shouldBe true

      val firstRank = result.toOption.get.ranks.head
      firstRank shouldBe Vector(
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

    "parse empty runs from digits 1 to 8 correctly" in {
    val input = "12311/8/8/8/8/8/8/8 w - - 0 1"

    val result = parse(input)

    result.isRight shouldBe true
    result.toOption.get.ranks.head shouldBe Vector.fill(8)(FenSquare.Empty)
    }

    "parse each castling symbol combination into flags" in {
      parse("8/8/8/8/8/8/8/8 w K - 0 1").toOption.get.castling shouldBe
        FenCastlingAvailability(true, false, false, false)

      parse("8/8/8/8/8/8/8/8 w Q - 0 1").toOption.get.castling shouldBe
        FenCastlingAvailability(false, true, false, false)

      parse("8/8/8/8/8/8/8/8 w k - 0 1").toOption.get.castling shouldBe
        FenCastlingAvailability(false, false, true, false)

      parse("8/8/8/8/8/8/8/8 w q - 0 1").toOption.get.castling shouldBe
        FenCastlingAvailability(false, false, false, true)

      parse("8/8/8/8/8/8/8/8 w Kq - 0 1").toOption.get.castling shouldBe
        FenCastlingAvailability(true, false, false, true)
    }

    "reject FEN with too few fields" in {
      val result = parse("8/8/8/8/8/8/8/8 w - - 0")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
    }

    "reject FEN with too many fields" in {
      val result = parse("8/8/8/8/8/8/8/8 w - - 0 1 extra")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
    }

    "reject FEN with double spaces because whitespace is strict" in {
      val result = parse("8/8/8/8/8/8/8/8  w - - 0 1")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
    }

    "reject FEN with missing spaces between fields" in {
      val result = parse("8/8/8/8/8/8/8/8w - - 0 1")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
    }

    "reject invalid active color" in {
      val result = parse("8/8/8/8/8/8/8/8 x - - 0 1")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
    }

    "reject invalid castling symbol" in {
      val result = parse("8/8/8/8/8/8/8/8 w A - 0 1")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
    }

    "reject duplicate castling symbols" in {
    val result = parse("8/8/8/8/8/8/8/8 w KK - 0 1")

    result.isLeft shouldBe true
    val err = result.left.toOption.get.asInstanceOf[ParseFailure.SyntaxError]
    err.message should include("castling field 'KK' contains duplicate symbols")
    err.message should include("[line 1, column")
    }

    "reject invalid en passant file" in {
      val result = parse("8/8/8/8/8/8/8/8 w - i3 0 1")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
    }

    "reject invalid en passant rank" in {
      val result = parse("8/8/8/8/8/8/8/8 w - e9 0 1")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
    }

    "reject malformed en passant token" in {
      val result = parse("8/8/8/8/8/8/8/8 w - ep 0 1")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
    }

    "parse halfmove clock zero" in {
      val result = parse("8/8/8/8/8/8/8/8 w - - 0 1")

      result.isRight shouldBe true
      result.toOption.get.halfmoveClock shouldBe 0
    }

    "reject non-numeric halfmove clock" in {
      val result = parse("8/8/8/8/8/8/8/8 w - - x 1")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
    }

    "parse fullmove number greater than zero" in {
      val result = parse("8/8/8/8/8/8/8/8 w - - 0 99")

      result.isRight shouldBe true
      result.toOption.get.fullmoveNumber shouldBe 99
    }

    "reject fullmove number zero" in {
      val result = parse("8/8/8/8/8/8/8/8 w - - 0 0")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
      result.left.toOption.get.asInstanceOf[ParseFailure.SyntaxError].message should include(
        "fullmove number must be a positive integer"
      )
    }

    "reject non-numeric fullmove number" in {
      val result = parse("8/8/8/8/8/8/8/8 w - - 0 x")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
    }

    "reject piece placement with too few ranks" in {
      val result = parse("8/8/8/8/8/8/8 w - - 0 1")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
    }

    "reject piece placement with too many ranks" in {
      val result = parse("8/8/8/8/8/8/8/8/8 w - - 0 1")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
    }

    "reject a rank that expands to 7 squares" in {
      val result = parse("7/8/8/8/8/8/8/8 w - - 0 1")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
      result.left.toOption.get.asInstanceOf[ParseFailure.SyntaxError].message should include(
        "rank expands to 7 squares; expected 8"
      )
    }

    "reject a rank that expands to 9 squares" in {
      val result = parse("81/8/8/8/8/8/8/8 w - - 0 1")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
      result.left.toOption.get.asInstanceOf[ParseFailure.SyntaxError].message should include(
        "rank expands to 9 squares; expected 8"
      )
    }

    "reject illegal piece symbols" in {
      val result = parse("x7/8/8/8/8/8/8/8 w - - 0 1")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
    }

    "reject rank containing zero as an empty-run digit" in {
      val result = parse("07/8/8/8/8/8/8/8 w - - 0 1")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
    }

    "reject rank containing digit 9 as an empty-run digit" in {
      val result = parse("9/8/8/8/8/8/8/8 w - - 0 1")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
    }

    "reject leftover trailing whitespace because parseAll requires full consumption" in {
      val result = parse("8/8/8/8/8/8/8/8 w - - 0 1 ")

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ParseFailure.SyntaxError]
    }

    "produce useful line and column information in parse failures" in {
      val result = parse("8/8/8/8/8/8/8/8 w KK - 0 1")

      result.isLeft shouldBe true

      val error = result.left.toOption.get.asInstanceOf[ParseFailure.SyntaxError]
      error.message should include("[line 1, column")
      error.message should include("failed parsing FEN")
    }
  }