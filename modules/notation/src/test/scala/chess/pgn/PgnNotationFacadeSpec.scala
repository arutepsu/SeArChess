package chess.notation.pgn

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import chess.application.ChessService
import chess.notation.api._

/** Specification for [[PgnNotationFacade]] as the canonical PGN boundary.
 *
 *  Covers:
 *  - Stage 1: facade is the only public PGN entry point
 *  - Stage 2: parsing produces a structured [[ParsedNotation.ParsedPgn]]
 *    with headers, move tokens, and result token correctly extracted
 *  - Stage 3/4 boundary: import and export wired to real implementations
 */
class PgnNotationFacadeSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  // ── Stage 1: canonical boundary ──────────────────────────────────────────────

  "PgnNotationFacade" should "return an ExportResult for PGN export of a new game" in {
    val state  = ChessService.createNewGame()
    val result = PgnNotationFacade.executeExport(state, NotationFormat.PGN)
    result.value shouldBe a[ExportResult]
    result.value.format shouldBe NotationFormat.PGN
    result.value.text   shouldBe "*"
  }

  it should "return a GameImportResult for a valid PGN import" in {
    val parsed = ParsedNotation.ParsedPgn("1. e4 e5", PgnData(Map.empty, Vector("e4", "e5"), None))
    val result = PgnNotationFacade.executeImport(parsed, ImportTarget.GameTarget)
    result.value shouldBe a[ImportResult.GameImportResult[?]]
  }

  it should "return StructuralError when asked to parse a non-PGN format" in {
    val result = PgnNotationFacade.parse(NotationFormat.FEN, "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    result.left.value shouldBe a[ParseFailure.StructuralError]
  }

  it should "return StructuralError for JSON format" in {
    val result = PgnNotationFacade.parse(NotationFormat.JSON, "{}")
    result.left.value shouldBe a[ParseFailure.StructuralError]
  }

  it should "propagate ParseFailure through parseAndImport for invalid PGN" in {
    val result = PgnNotationFacade.parseAndImport(NotationFormat.PGN, "", ImportTarget.GameTarget)
    result.left.value shouldBe a[ParseFailure.UnexpectedEndOfInput]
  }

  // ── Stage 2: empty and blank input ───────────────────────────────────────────

  it should "return UnexpectedEndOfInput for empty input" in {
    val result = PgnNotationFacade.parse(NotationFormat.PGN, "")
    result.left.value shouldBe a[ParseFailure.UnexpectedEndOfInput]
  }

  it should "return UnexpectedEndOfInput for blank (whitespace-only) input" in {
    val result = PgnNotationFacade.parse(NotationFormat.PGN, "   \n  ")
    result.left.value shouldBe a[ParseFailure.UnexpectedEndOfInput]
  }

  // ── Stage 2: parse produces ParsedPgn ────────────────────────────────────────

  it should "produce a ParsedPgn for a bare move list without headers" in {
    val result = PgnNotationFacade.parse(NotationFormat.PGN, "1. e4 e5 2. Nf3 Nc6")
    result.value shouldBe a[ParsedNotation.ParsedPgn]
    result.value.kind shouldBe ParsedNotationKind.Pgn
  }

  it should "preserve the raw input in ParsedPgn.raw" in {
    val input  = "1. e4 e5"
    val result = PgnNotationFacade.parse(NotationFormat.PGN, input)
    result.value.raw shouldBe input
  }

  // ── Stage 2: header extraction ───────────────────────────────────────────────

  it should "extract headers from a standard PGN document" in {
    val pgn =
      """[Event "Test match"]
        |[White "Alice"]
        |[Black "Bob"]
        |
        |1. e4 e5 1-0""".stripMargin
    val parsed = PgnNotationFacade.parse(NotationFormat.PGN, pgn).value.asInstanceOf[ParsedNotation.ParsedPgn]
    parsed.data.headers("Event") shouldBe "Test match"
    parsed.data.headers("White") shouldBe "Alice"
    parsed.data.headers("Black") shouldBe "Bob"
  }

  it should "produce empty headers for a bare move list" in {
    val parsed = PgnNotationFacade.parse(NotationFormat.PGN, "1. e4 e5").value
      .asInstanceOf[ParsedNotation.ParsedPgn]
    parsed.data.headers shouldBe empty
  }

  it should "parse a header-only PGN with no moves" in {
    val pgn    = "[Event \"?\"]\n[White \"?\"]\n[Black \"?\"]"
    val parsed = PgnNotationFacade.parse(NotationFormat.PGN, pgn).value
      .asInstanceOf[ParsedNotation.ParsedPgn]
    parsed.data.headers    should have size 3
    parsed.data.moveTokens shouldBe empty
    parsed.data.result     shouldBe None
  }

  // ── Stage 2: move token extraction ───────────────────────────────────────────

  it should "extract SAN tokens from move text, stripping move numbers" in {
    val parsed = PgnNotationFacade.parse(NotationFormat.PGN, "1. e4 e5 2. Nf3 Nc6").value
      .asInstanceOf[ParsedNotation.ParsedPgn]
    parsed.data.moveTokens shouldBe Vector("e4", "e5", "Nf3", "Nc6")
  }

  it should "strip comments from move text" in {
    val pgn    = "1. e4 {best by test} e5 2. Nf3 Nc6"
    val parsed = PgnNotationFacade.parse(NotationFormat.PGN, pgn).value
      .asInstanceOf[ParsedNotation.ParsedPgn]
    parsed.data.moveTokens shouldBe Vector("e4", "e5", "Nf3", "Nc6")
  }

  it should "strip non-nested variations from move text" in {
    val pgn    = "1. e4 (1. d4 d5) e5 2. Nf3"
    val parsed = PgnNotationFacade.parse(NotationFormat.PGN, pgn).value
      .asInstanceOf[ParsedNotation.ParsedPgn]
    parsed.data.moveTokens shouldBe Vector("e4", "e5", "Nf3")
  }

  it should "strip NAGs from move text" in {
    val pgn    = "1. e4$1 e5$2 2. Nf3$6"
    val parsed = PgnNotationFacade.parse(NotationFormat.PGN, pgn).value
      .asInstanceOf[ParsedNotation.ParsedPgn]
    parsed.data.moveTokens shouldBe Vector("e4", "e5", "Nf3")
  }

  // ── Stage 2: result token extraction ─────────────────────────────────────────

  it should "extract '1-0' result token and exclude it from moveTokens" in {
    val parsed = PgnNotationFacade.parse(NotationFormat.PGN, "1. e4 e5 1-0").value
      .asInstanceOf[ParsedNotation.ParsedPgn]
    parsed.data.result     shouldBe Some("1-0")
    parsed.data.moveTokens should not contain "1-0"
  }

  it should "extract '0-1' result token" in {
    val parsed = PgnNotationFacade.parse(NotationFormat.PGN, "1. e4 e5 0-1").value
      .asInstanceOf[ParsedNotation.ParsedPgn]
    parsed.data.result shouldBe Some("0-1")
  }

  it should "extract '1/2-1/2' result token" in {
    val parsed = PgnNotationFacade.parse(NotationFormat.PGN, "1. e4 e5 1/2-1/2").value
      .asInstanceOf[ParsedNotation.ParsedPgn]
    parsed.data.result shouldBe Some("1/2-1/2")
  }

  it should "extract '*' result token" in {
    val parsed = PgnNotationFacade.parse(NotationFormat.PGN, "1. e4 e5 *").value
      .asInstanceOf[ParsedNotation.ParsedPgn]
    parsed.data.result shouldBe Some("*")
  }

  it should "produce None result when no result token is present" in {
    val parsed = PgnNotationFacade.parse(NotationFormat.PGN, "1. e4 e5").value
      .asInstanceOf[ParsedNotation.ParsedPgn]
    parsed.data.result shouldBe None
  }

  // ── Stage 2: full PGN document ───────────────────────────────────────────────

  it should "parse a realistic seven-tag-roster PGN correctly" in {
    val pgn =
      """[Event "World Championship"]
        |[Site "London"]
        |[Date "2024.01.01"]
        |[Round "1"]
        |[White "Player A"]
        |[Black "Player B"]
        |[Result "1-0"]
        |
        |1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 1-0""".stripMargin
    val parsed = PgnNotationFacade.parse(NotationFormat.PGN, pgn).value
      .asInstanceOf[ParsedNotation.ParsedPgn]
    parsed.data.headers("White")  shouldBe "Player A"
    parsed.data.headers("Result") shouldBe "1-0"
    parsed.data.moveTokens        shouldBe Vector("e4", "e5", "Nf3", "Nc6", "Bb5", "a6")
    parsed.data.result            shouldBe Some("1-0")
  }
