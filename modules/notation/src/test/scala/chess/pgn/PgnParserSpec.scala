package chess.notation.pgn

import chess.notation.api.{NotationFormat, ParsedNotation, ParseFailure, PgnData}
import org.scalatest.EitherValues
import org.scalatest.EitherValues.convertLeftProjectionToValuable
import org.scalatest.Assertions.fail
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class PgnParserSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val SimplePgn =
    """[Event "Friendly"]
      |[White "Alice"]
      |[Black "Bob"]
      |
      |1. e4 e5 2. Nf3 Nc6 1-0
      |""".stripMargin

  private def parsedPgn(input: String): ParsedNotation.ParsedPgn =
    PgnNotationFacade.parse(chess.notation.api.NotationFormat.PGN, input).value match
      case pgn: ParsedNotation.ParsedPgn => pgn
      case other                         => fail(s"Expected ParsedPgn, got $other")

  "PgnParser" should "parse PGN data successfully" in {
    val data = PgnParser.parse(SimplePgn).value
    data shouldBe a[PgnData]
    data.headers("Event") shouldBe "Friendly"
    data.moveTokens shouldBe Vector("e4", "e5", "Nf3", "Nc6")
    data.result shouldBe Some("1-0")
  }

  it should "preserve the raw input string when wrapped as ParsedPgn" in {
    parsedPgn(SimplePgn).raw shouldBe SimplePgn
  }

  it should "declare PGN through the facade path" in {
    val parsed = PgnNotationFacade.parse(chess.notation.api.NotationFormat.PGN, SimplePgn).value
    parsed shouldBe a[ParsedNotation.ParsedPgn]
  }

  it should "reject blank input" in {
    PgnParser.parse("").left.value shouldBe ParseFailure.UnexpectedEndOfInput("PGN input is empty")
  }

  it should "parse bare movetext with no headers" in {
    val data = PgnParser.parse("1. d4 d5 2. c4 *").value
    data.headers shouldBe Map.empty
    data.moveTokens shouldBe Vector("d4", "d5", "c4")
    data.result shouldBe Some("*")
  }

  it should "allow headers-only PGN with no moves" in {
    val data =
      PgnParser
        .parse(
          """[Event "HeaderOnly"]
          |[White "Alice"]
          |""".stripMargin
        )
        .value

    data.headers shouldBe Map("Event" -> "HeaderOnly", "White" -> "Alice")
    data.moveTokens shouldBe Vector.empty
    data.result shouldBe None
  }
