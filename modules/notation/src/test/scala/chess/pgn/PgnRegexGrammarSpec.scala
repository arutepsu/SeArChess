package chess.notation.pgn

import chess.notation.api.ParseFailure
import org.scalatest.EitherValues
import org.scalatest.EitherValues.convertLeftProjectionToValuable
import org.scalatest.Assertions.fail
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class PgnRegexGrammarSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def parse(input: String): Either[ParseFailure, PgnRecord] =
    PgnRegexGrammar.parseRecord(input)

  "PgnRegexGrammar.parseRecord" should "reject blank input" in {
    parse("").left.value shouldBe ParseFailure.UnexpectedEndOfInput("PGN input is empty")
    parse("   \n\t  ").left.value shouldBe ParseFailure.UnexpectedEndOfInput("PGN input is empty")
  }

  it should "parse a bare movelist with a result" in {
    val input = "1. e4 e5 2. Nf3 Nc6 1-0"

    val record = parse(input).value
    record.headers shouldBe Map.empty
    record.moveTokens shouldBe Vector("e4", "e5", "Nf3", "Nc6")
    record.result shouldBe Some("1-0")
  }

  it should "parse headers and movetext together" in {
    val input =
      """[Event "Friendly"]
        |[White "Alice"]
        |[Black "Bob"]
        |
        |1. e4 e5 2. Nf3 Nc6 0-1
        |""".stripMargin

    val record = parse(input).value
    record.headers shouldBe Map(
      "Event" -> "Friendly",
      "White" -> "Alice",
      "Black" -> "Bob"
    )
    record.moveTokens shouldBe Vector("e4", "e5", "Nf3", "Nc6")
    record.result shouldBe Some("0-1")
  }

  it should "accept headers only and return an empty move list" in {
    val input =
      """[Event "HeaderOnly"]
        |[White "Alice"]
        |[Black "Bob"]
        |""".stripMargin

    val record = parse(input).value
    record.headers shouldBe Map(
      "Event" -> "HeaderOnly",
      "White" -> "Alice",
      "Black" -> "Bob"
    )
    record.moveTokens shouldBe Vector.empty
    record.result shouldBe None
  }

  it should "accept a bare movelist with no headers" in {
    val input = "1. d4 d5 2. c4"

    val record = parse(input).value
    record.headers shouldBe Map.empty
    record.moveTokens shouldBe Vector("d4", "d5", "c4")
    record.result shouldBe None
  }

  it should "strip comments from movetext" in {
    val input = "1. e4 {best by test} e5 2. Nf3 {develops} Nc6 *"

    val record = parse(input).value
    record.moveTokens shouldBe Vector("e4", "e5", "Nf3", "Nc6")
    record.result shouldBe Some("*")
  }

  it should "strip NAGs from movetext" in {
    val input = "1. e4 $1 e5 $2 2. Nf3 $5 Nc6 1/2-1/2"

    val record = parse(input).value
    record.moveTokens shouldBe Vector("e4", "e5", "Nf3", "Nc6")
    record.result shouldBe Some("1/2-1/2")
  }

  it should "strip a simple variation from movetext" in {
    val input = "1. e4 (1. d4 d5) e5 2. Nf3 Nc6 *"

    val record = parse(input).value
    record.moveTokens shouldBe Vector("e4", "e5", "Nf3", "Nc6")
    record.result shouldBe Some("*")
  }

  it should "strip nested variations iteratively" in {
    val input = "1. e4 (1. d4 (1... Nf6) d5) e5 2. Nf3 Nc6 *"

    val record = parse(input).value
    record.moveTokens shouldBe Vector("e4", "e5", "Nf3", "Nc6")
    record.result shouldBe Some("*")
  }

  it should "ignore move numbers in both white and black notation styles" in {
    val input = "1. e4 e5 2... Nf3 Nc6 *"

    val record = parse(input).value
    record.moveTokens shouldBe Vector("e4", "e5", "Nf3", "Nc6")
    record.result shouldBe Some("*")
  }

  it should "keep SAN tokens intact for later semantic resolution" in {
    val input = "1. O-O O-O-O 2. exd5 Nxd5 3. e8=Q+ *"

    val record = parse(input).value
    record.moveTokens shouldBe Vector("O-O", "O-O-O", "exd5", "Nxd5", "e8=Q+")
    record.result shouldBe Some("*")
  }

  it should "extract quoted header values including spaces" in {
    val input =
      """[Event "World Championship"]
        |[Site "New York City"]
        |
        |1. e4 e5 *
        |""".stripMargin

    val record = parse(input).value
    record.headers("Event") shouldBe "World Championship"
    record.headers("Site") shouldBe "New York City"
  }

  it should "keep the last duplicate header key because headers are stored as a Map" in {
    val input =
      """[Event "First"]
        |[Event "Second"]
        |
        |1. e4 *
        |""".stripMargin

    val record = parse(input).value
    record.headers("Event") shouldBe "Second"
  }

  it should "return the first result token found" in {
    val input = "1. e4 e5 * 1-0"

    val record = parse(input).value
    record.result shouldBe Some("*")
    record.moveTokens shouldBe Vector("e4", "e5")
  }

  it should "not invent a result token when none is present" in {
    val input = "1. e4 e5 2. Nf3 Nc6"

    val record = parse(input).value
    record.result shouldBe None
    record.moveTokens shouldBe Vector("e4", "e5", "Nf3", "Nc6")
  }

  it should "tolerate extra whitespace and blank lines" in {
    val input =
      """

        |[Event "Friendly"]

        |1.   e4    e5

        |2. Nf3     Nc6

        |*
        |""".stripMargin

    val record = parse(input).value
    record.headers shouldBe Map("Event" -> "Friendly")
    record.moveTokens shouldBe Vector("e4", "e5", "Nf3", "Nc6")
    record.result shouldBe Some("*")
  }
