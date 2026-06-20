package chess.adapter.gui.notation

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.application.GameStateCommandService

/** Contracts for the [[GuiNotationOutcome]] sealed hierarchy and its supporting types.
  *
  * These tests pin the data-type contracts — defaults, membership in the sealed hierarchy,
  * exhaustive category coverage — independently of [[GuiNotationApi]] integration. They serve as
  * executable documentation of what GUI callers can rely on.
  */
class GuiNotationOutcomeSpec extends AnyFlatSpec with Matchers:

  private val freshState = GameStateCommandService.createNewGame()

  // ── ImportSuccess: default warnings ─────────────────────────────────────────

  "GuiNotationOutcome.ImportSuccess" should "default warnings to Nil when not supplied" in {
    val outcome = GuiNotationOutcome.ImportSuccess(freshState)
    outcome.warnings shouldBe Nil
  }

  it should "accept an explicit Nil warnings list" in {
    val outcome = GuiNotationOutcome.ImportSuccess(freshState, Nil)
    outcome.warnings shouldBe Nil
  }

  it should "treat Nil warnings as a clean import with no observations" in {
    val outcome = GuiNotationOutcome.ImportSuccess(freshState)
    outcome.warnings.isEmpty shouldBe true
  }

  it should "preserve a non-empty warnings list" in {
    val w = GuiNotationWarning("clock rounded", GuiWarningCategory.Normalization)
    val outcome = GuiNotationOutcome.ImportSuccess(freshState, List(w))
    outcome.warnings shouldBe List(w)
  }

  it should "be a GuiNotationOutcome" in {
    val outcome: GuiNotationOutcome = GuiNotationOutcome.ImportSuccess(freshState)
    outcome shouldBe a[GuiNotationOutcome.ImportSuccess]
  }

  it should "expose the imported state" in {
    val outcome = GuiNotationOutcome.ImportSuccess(freshState)
    outcome.state shouldBe freshState
  }

  // ── Failure: default category ─────────────────────────────────────────────

  "GuiNotationOutcome.Failure" should "default category to InvalidInput when not supplied" in {
    val failure = GuiNotationOutcome.Failure("bad input")
    failure.category shouldBe FailureCategory.InvalidInput
  }

  it should "default details to None when not supplied" in {
    val failure = GuiNotationOutcome.Failure("bad input")
    failure.details shouldBe None
  }

  it should "default both details and category when only message is given" in {
    val failure = GuiNotationOutcome.Failure("something failed")
    failure.details shouldBe None
    failure.category shouldBe FailureCategory.InvalidInput
  }

  it should "carry an explicit details value when supplied" in {
    val failure = GuiNotationOutcome.Failure("bad input", details = Some("Line 3, column 7"))
    failure.details shouldBe Some("Line 3, column 7")
  }

  it should "carry an explicit category when supplied" in {
    val failure =
      GuiNotationOutcome.Failure("not implemented", category = FailureCategory.UnavailableFeature)
    failure.category shouldBe FailureCategory.UnavailableFeature
  }

  it should "allow all FailureCategory values as category" in {
    FailureCategory.values.foreach { cat =>
      val failure = GuiNotationOutcome.Failure("msg", category = cat)
      failure.category shouldBe cat
    }
  }

  it should "be a GuiNotationOutcome" in {
    val failure: GuiNotationOutcome = GuiNotationOutcome.Failure("oops")
    failure shouldBe a[GuiNotationOutcome.Failure]
  }

  it should "preserve the message string exactly" in {
    val msg = "Unexpected token at position 12"
    GuiNotationOutcome.Failure(msg).message shouldBe msg
  }

  // ── Failure: each FailureCategory carries distinct semantics ─────────────

  "FailureCategory.InvalidInput" should "represent a syntax or structural parse problem" in {
    val f =
      GuiNotationOutcome.Failure("missing rank separator", category = FailureCategory.InvalidInput)
    f.category shouldBe FailureCategory.InvalidInput
  }

  "FailureCategory.SemanticError" should "represent a semantically illegal position" in {
    val f =
      GuiNotationOutcome.Failure("both kings in check", category = FailureCategory.SemanticError)
    f.category shouldBe FailureCategory.SemanticError
  }

  "FailureCategory.UnsupportedInput" should "represent an unsupported dialect or version" in {
    val f = GuiNotationOutcome.Failure(
      "Chess960 not supported",
      category = FailureCategory.UnsupportedInput
    )
    f.category shouldBe FailureCategory.UnsupportedInput
  }

  "FailureCategory.UnavailableFeature" should "represent a not-yet-implemented operation" in {
    val f = GuiNotationOutcome.Failure(
      "PGN import not available",
      category = FailureCategory.UnavailableFeature
    )
    f.category shouldBe FailureCategory.UnavailableFeature
  }

  // ── FailureCategory exhaustiveness ────────────────────────────────────────

  "FailureCategory" should "have exactly four cases" in {
    FailureCategory.values should have size 4
  }

  it should "contain InvalidInput, SemanticError, UnsupportedInput, UnavailableFeature" in {
    FailureCategory.values.toSet shouldBe Set(
      FailureCategory.InvalidInput,
      FailureCategory.SemanticError,
      FailureCategory.UnsupportedInput,
      FailureCategory.UnavailableFeature
    )
  }

  // ── ExportSuccess ─────────────────────────────────────────────────────────

  "GuiNotationOutcome.ExportSuccess" should "carry the serialised text" in {
    val outcome =
      GuiNotationOutcome.ExportSuccess("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    outcome.text should not be empty
  }

  it should "be a GuiNotationOutcome" in {
    val outcome: GuiNotationOutcome = GuiNotationOutcome.ExportSuccess("some-fen")
    outcome shouldBe a[GuiNotationOutcome.ExportSuccess]
  }

  // ── Sealed hierarchy exhaustiveness ───────────────────────────────────────

  "GuiNotationOutcome" should "be exhaustively matchable with three cases" in {
    def label(o: GuiNotationOutcome): String = o match
      case _: GuiNotationOutcome.ImportSuccess => "import-success"
      case _: GuiNotationOutcome.ExportSuccess => "export-success"
      case _: GuiNotationOutcome.Failure       => "failure"

    label(GuiNotationOutcome.ImportSuccess(freshState)) shouldBe "import-success"
    label(GuiNotationOutcome.ExportSuccess("text")) shouldBe "export-success"
    label(GuiNotationOutcome.Failure("msg")) shouldBe "failure"
  }

  // ── GuiNotationWarning ────────────────────────────────────────────────────

  "GuiNotationWarning" should "carry message and category" in {
    val w = GuiNotationWarning("ep square dropped", GuiWarningCategory.DataLoss)
    w.message shouldBe "ep square dropped"
    w.category shouldBe GuiWarningCategory.DataLoss
  }

  // ── GuiWarningCategory exhaustiveness ────────────────────────────────────

  "GuiWarningCategory" should "have exactly three cases" in {
    GuiWarningCategory.values should have size 3
  }

  it should "contain Informational, DataLoss, Normalization" in {
    GuiWarningCategory.values.toSet shouldBe Set(
      GuiWarningCategory.Informational,
      GuiWarningCategory.DataLoss,
      GuiWarningCategory.Normalization
    )
  }
