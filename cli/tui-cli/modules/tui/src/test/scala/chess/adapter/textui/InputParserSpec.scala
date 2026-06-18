package chess.adapter.textui

import chess.domain.model.PieceType
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class InputParserSpec extends AnyFlatSpec with Matchers with EitherValues:

  "InputParser.parse" should "parse 'new'" in {
    InputParser.parse("new") shouldBe Right(TextUiCommand.New)
  }

  it should "parse 'show'" in {
    InputParser.parse("show") shouldBe Right(TextUiCommand.Show)
  }

  it should "parse 'help'" in {
    InputParser.parse("help") shouldBe Right(TextUiCommand.Help)
  }

  it should "parse 'quit'" in {
    InputParser.parse("quit") shouldBe Right(TextUiCommand.Quit)
  }

  it should "parse a valid move 'move e2 e4'" in {
    InputParser.parse("move e2 e4") shouldBe Right(TextUiCommand.MoveCmd("e2", "e4"))
  }

  it should "parse a valid move with extra surrounding whitespace" in {
    InputParser.parse("  move  a1  h8  ") shouldBe Right(TextUiCommand.MoveCmd("a1", "h8"))
  }

  it should "return EmptyInput for blank input" in {
    InputParser.parse("   ").left.value shouldBe InputParseError.EmptyInput
  }

  it should "return EmptyInput for empty string" in {
    InputParser.parse("").left.value shouldBe InputParseError.EmptyInput
  }

  it should "return UnknownCommand for unrecognised commands" in {
    InputParser.parse("castle").left.value shouldBe InputParseError.UnknownCommand("castle")
  }

  it should "return WrongArgumentCount when move has no arguments" in {
    InputParser.parse("move").left.value shouldBe InputParseError.WrongArgumentCount("move")
  }

  it should "return WrongArgumentCount when move has only one argument" in {
    InputParser.parse("move e2").left.value shouldBe InputParseError.WrongArgumentCount("move")
  }

  it should "return WrongArgumentCount when move has three arguments" in {
    InputParser.parse("move e2 e4 e6").left.value shouldBe InputParseError.WrongArgumentCount(
      "move"
    )
  }

  it should "pass through unvalidated square tokens (validation deferred to domain)" in {
    // InputParser no longer validates chess semantics — invalid squares become MoveCmd
    InputParser.parse("move z9 e4") shouldBe Right(TextUiCommand.MoveCmd("z9", "e4"))
    InputParser.parse("move e2 z9") shouldBe Right(TextUiCommand.MoveCmd("e2", "z9"))
    InputParser.parse("move e22 e4") shouldBe Right(TextUiCommand.MoveCmd("e22", "e4"))
  }

  it should "accept all corner squares as valid" in {
    InputParser.parse("move a1 h8") shouldBe Right(TextUiCommand.MoveCmd("a1", "h8"))
    InputParser.parse("move h1 a8") shouldBe Right(TextUiCommand.MoveCmd("h1", "a8"))
  }

  // ── promote command ────────────────────────────────────────────────────────

  it should "parse 'promote q'" in {
    InputParser.parse("promote q") shouldBe Right(TextUiCommand.PromoteCmd(PieceType.Queen))
  }

  it should "parse 'promote queen' (full word)" in {
    InputParser.parse("promote queen") shouldBe Right(TextUiCommand.PromoteCmd(PieceType.Queen))
  }

  it should "parse 'promote r'" in {
    InputParser.parse("promote r") shouldBe Right(TextUiCommand.PromoteCmd(PieceType.Rook))
  }

  it should "parse 'promote rook' (full word)" in {
    InputParser.parse("promote rook") shouldBe Right(TextUiCommand.PromoteCmd(PieceType.Rook))
  }

  it should "parse 'promote b'" in {
    InputParser.parse("promote b") shouldBe Right(TextUiCommand.PromoteCmd(PieceType.Bishop))
  }

  it should "parse 'promote bishop' (full word)" in {
    InputParser.parse("promote bishop") shouldBe Right(TextUiCommand.PromoteCmd(PieceType.Bishop))
  }

  it should "parse 'promote n'" in {
    InputParser.parse("promote n") shouldBe Right(TextUiCommand.PromoteCmd(PieceType.Knight))
  }

  it should "parse 'promote knight' (full word)" in {
    InputParser.parse("promote knight") shouldBe Right(TextUiCommand.PromoteCmd(PieceType.Knight))
  }

  it should "parse 'promote' case-insensitively" in {
    InputParser.parse("promote Q") shouldBe Right(TextUiCommand.PromoteCmd(PieceType.Queen))
  }

  it should "return InvalidPromotionToken for 'promote k'" in {
    InputParser.parse("promote k").left.value shouldBe InputParseError.InvalidPromotionToken("k")
  }

  it should "return InvalidPromotionToken for 'promote p'" in {
    InputParser.parse("promote p").left.value shouldBe InputParseError.InvalidPromotionToken("p")
  }

  it should "return InvalidPromotionToken for an unknown promotion token" in {
    InputParser.parse("promote frog").left.value shouldBe InputParseError.InvalidPromotionToken(
      "frog"
    )
  }

  it should "return WrongArgumentCount for 'promote' with no arguments" in {
    InputParser.parse("promote").left.value shouldBe InputParseError.WrongArgumentCount("promote")
  }

  it should "return WrongArgumentCount for 'promote' with two arguments" in {
    InputParser.parse("promote q q").left.value shouldBe InputParseError.WrongArgumentCount(
      "promote"
    )
  }
