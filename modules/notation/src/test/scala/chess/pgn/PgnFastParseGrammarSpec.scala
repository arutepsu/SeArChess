package chess.notation.pgn

import chess.notation.api.ParseFailure
import org.scalatest.Assertions.fail
import org.scalatest.EitherValues
import org.scalatest.EitherValues.convertLeftProjectionToValuable
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues.convertOptionToValuable 

final class PgnFastParseGrammarSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def parse(input: String): Either[ParseFailure, PgnRecord] =
    PgnFastParseGrammar.parseRecord(input)

  private def syntaxError(input: String): ParseFailure.SyntaxError =
    parse(input).left.value match
      case err: ParseFailure.SyntaxError => err
      case other                         => fail(s"Expected SyntaxError, got $other")

  private def syntaxErrorMessage(input: String): String =
    syntaxError(input).message

  "PgnFastParseGrammar.parseRecord" should "reject blank input" in {
    parse("").left.value shouldBe ParseFailure.UnexpectedEndOfInput("PGN input is empty")
  }

  it should "reject whitespace-only input" in {
    parse("   \n\t  ").left.value shouldBe ParseFailure.UnexpectedEndOfInput("PGN input is empty")
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
    val input = "1. O-O O-O-O 2. exd5 Nxd5 3. e8=Q+ *"
    val record = parse(input).value
    record.moveTokens shouldBe Vector("O-O", "O-O-O", "exd5", "Nxd5", "e8=Q+")
    record.result shouldBe Some("*")
  }

  it should "accept movetext without a result token" in {
    val input = "1. e4 e5 2. Nf3 Nc6"
    val record = parse(input).value
    record.headers shouldBe Map.empty
    record.moveTokens shouldBe Vector("e4", "e5", "Nf3", "Nc6")
    record.result shouldBe None
  }

  it should "accept headers without movetext and return an empty move section" in {
    val input =
      """[Event "Friendly"]
        |[White "Alice"]
        |[Black "Bob"]
        |""".stripMargin

    val record = parse(input).value
    record.headers shouldBe Map(
      "Event" -> "Friendly",
      "White" -> "Alice",
      "Black" -> "Bob"
    )
    record.moveTokens shouldBe Vector.empty
    record.result shouldBe None
  }

  it should "ignore move numbers and only keep SAN tokens and result" in {
    val input = "1. e4 e5 2... Nf3 Nc6 *"
    val record = parse(input).value
    record.moveTokens shouldBe Vector("e4", "e5", "Nf3", "Nc6")
    record.result shouldBe Some("*")
  }

  it should "allow duplicate header keys with last one winning in the final map" in {
    val input =
      """[Event "First"]
        |[Event "Second"]
        |1. e4 e5 *
        |""".stripMargin

    val record = parse(input).value
    record.headers shouldBe Map("Event" -> "Second")
    record.moveTokens shouldBe Vector("e4", "e5")
    record.result shouldBe Some("*")
  }

  it should "return SyntaxError for malformed header syntax" in {
    val err = syntaxError("""[Event "Friendly" 1. e4 e5 1-0""")
    err.message should include("failed parsing PGN")
    err.line shouldBe Some(1)
    err.column should not be empty
  }

  it should "return SyntaxError for an unterminated quoted header value" in {
    val err = syntaxError("""[White "Alice] 1. e4 e5 1-0""")
    err.message should include("failed parsing PGN")
    err.line shouldBe Some(1)
    err.column should not be empty
  }

  it should "return SyntaxError for an invalid tag key" in {
    val err = syntaxError("""[123 "Alice"] 1. e4 e5 1-0""")
    err.message should include("failed parsing PGN")
    err.line shouldBe Some(1)
    err.column should not be empty
  }

  it should "return SyntaxError for stray closing bracket in movetext" in {
    val err = syntaxError("""1. e4 ] e5 1-0""")
    err.message should include("failed parsing PGN")
    err.line shouldBe Some(1)
    err.column should not be empty
  }

  it should "return SyntaxError for stray opening bracket in movetext" in {
    val err = syntaxError("""1. e4 [ e5 1-0""")
    err.message should include("failed parsing PGN")
    err.line shouldBe Some(1)
    err.column should not be empty
  }

  it should "treat an invalid result-like token as a SAN token because SAN is intentionally permissive" in {
    val record = parse("""1. e4 e5 2. Nf3 Nc6 2-0""").value
    record.moveTokens shouldBe Vector("e4", "e5", "Nf3", "Nc6", "2-0")
    record.result shouldBe None
  }

  it should "treat 2-0 as a SAN token because SAN parsing is intentionally permissive" in {
    val record = parse("""1. e4 e5 2. Nf3 Nc6 2-0""").value
    record.moveTokens shouldBe Vector("e4", "e5", "Nf3", "Nc6", "2-0")
    record.result shouldBe None
  }

  it should "include line and column information in syntax errors" in {
    val err = syntaxError("""[Event "Friendly] 1. e4 e5 1-0""")
    err.message should include("[line 1, column")
    err.message should include("failed parsing PGN")
    err.line shouldBe Some(1)
    err.column.value should be >= 1
  }

  it should "report a column near the malformed token" in {
    val err = syntaxError("""[Event "Friendly] 1. e4 e5 1-0""")
    err.column.value should be > 1
  }

  it should "surface a useful FastParse-derived failure message" in {
    syntaxErrorMessage("""[Event "Friendly] 1. e4 e5 1-0""") should include("failed parsing PGN")
  }