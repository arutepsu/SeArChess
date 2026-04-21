package chess.notation.pgn

import chess.notation.api.ParseFailure
import org.scalatest.EitherValues
import org.scalatest.EitherValues.convertLeftProjectionToValuable
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class PgnCombinatorGrammarSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def parse(input: String): Either[ParseFailure, PgnRecord] =
    PgnCombinatorGrammar.parseRecord(input)

  "PgnCombinatorGrammar.parseRecord" should "reject blank input" in {
    parse("").left.value shouldBe ParseFailure.UnexpectedEndOfInput("PGN input is empty")
  }

  it should "parse a bare movelist with a result" in {
    val record = parse("1. e4 e5 2. Nf3 Nc6 1-0").value
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

  it should "strip comments, NAGs, and variations before parsing" in {
    val input =
      """[Event "Test"]
        |1. e4 {comment} e5 $1 (1... c5) 2. Nf3 Nc6 *
        |""".stripMargin

    val record = parse(input).value
    record.headers shouldBe Map("Event" -> "Test")
    record.moveTokens shouldBe Vector("e4", "e5", "Nf3", "Nc6")
    record.result shouldBe Some("*")
  }

  it should "keep SAN tokens intact for later semantic resolution" in {
    val record = parse("1. O-O O-O-O 2. exd5 Nxd5 3. e8=Q+ *").value
    record.moveTokens shouldBe Vector("O-O", "O-O-O", "exd5", "Nxd5", "e8=Q+")
    record.result shouldBe Some("*")
  }
