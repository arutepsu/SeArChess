package chess.adapter.gui.notation

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.application.ChessService
import chess.domain.state.GameState
import chess.domain.model.Color
import chess.notation.api.{
  ImportResult,
  ImportTarget,
  NotationFacade,
  NotationFailure,
  NotationFormat,
  NotationWarning,
  ParsedNotation,
  ParseFailure
}

/** Tests for the pure [[NotationSidebarController.transition]] function.
  *
  * All tests run without JavaFX — only the pure companion object is exercised.
  *
  * Notation-layer ADTs appear only in stub facades within this spec, confirming that the widget
  * contract itself is insulated from those types.
  */
class NotationSidebarControllerSpec extends AnyFlatSpec with Matchers:

  private val InitialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val freshGame = ChessService.createNewGame()
  private val blankState = NotationSidebarState()
  private val defaultApi = GuiNotationApi.default

  // ── helpers ────────────────────────────────────────────────────────────────

  private def transit(
      sidebarState: NotationSidebarState = blankState,
      action: SidebarAction,
      api: GuiNotationApi = defaultApi,
      game: GameState = freshGame
  ): (NotationSidebarState, Option[GameState]) =
    NotationSidebarController.transition(sidebarState, action, api, game)

  private def request(id: NotationActionId): SidebarAction =
    SidebarAction.NotationActionRequested(id)

  // ── InputTextChanged ───────────────────────────────────────────────────────

  "NotationSidebarController.transition" should "update inputText on InputTextChanged" in {
    val (next, importedOpt) = transit(action = SidebarAction.InputTextChanged("hello"))
    next.inputText shouldBe "hello"
    importedOpt shouldBe None
  }

  it should "not touch other state fields on InputTextChanged" in {
    val initial = blankState.copy(feedback = Some(SidebarFeedback.Success("prev")))
    val (next, _) = transit(sidebarState = initial, action = SidebarAction.InputTextChanged("x"))
    next.feedback shouldBe initial.feedback
    next.outputText shouldBe None
    next.warnings shouldBe Nil
  }

  // ── FEN import: success ────────────────────────────────────────────────────

  it should "return ImportSuccess-derived state and Some(GameState) for a valid FEN" in {
    val withText = blankState.copy(inputText = InitialFen)
    val (next, importedOpt) =
      transit(sidebarState = withText, action = request(NotationActionId.FenImport))
    importedOpt should not be empty
    importedOpt.getOrElse(fail("expected Some GameState")).currentPlayer shouldBe Color.White
    next.feedback shouldBe Some(SidebarFeedback.Success("Position imported successfully."))
    next.outputText shouldBe None
  }

  it should "carry structured warnings from a successful import" in {
    val warningFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        chess.notation.fen.FenParser.parse(InitialFen)
      def executeImport(
          parsed: ParsedNotation,
          target: ImportTarget
      ): Either[NotationFailure, ImportResult[GameState]] =
        chess.notation.fen.FenImporter.importNotation(parsed, target).map {
          case r: ImportResult.PositionImportResult[GameState @unchecked] =>
            r.copy(warnings =
              List(NotationWarning.NormalizationApplied("normalised halfmove clock"))
            )
          case other => other
        }

    val api = GuiNotationApi(warningFacade)
    val (next, importedOpt) = transit(
      sidebarState = blankState.copy(inputText = InitialFen),
      action = request(NotationActionId.FenImport),
      api = api
    )
    importedOpt should not be empty
    next.warnings should have size 1
    next.warnings.head.category shouldBe GuiWarningCategory.Normalization
  }

  // ── FEN import: failure ────────────────────────────────────────────────────

  it should "return Failure-derived state and None for an invalid FEN" in {
    val withBadText = blankState.copy(inputText = "not valid fen")
    val (next, importedOpt) =
      transit(sidebarState = withBadText, action = request(NotationActionId.FenImport))
    importedOpt shouldBe None
    next.feedback should matchPattern {
      case Some(SidebarFeedback.Failure(_, _, FailureCategory.InvalidInput)) =>
    }
    next.warnings shouldBe Nil
  }

  it should "return Failure(SemanticError) for a syntactically valid but semantically invalid FEN" in {
    val noBlackKing = "8/8/8/8/8/8/8/4K3 w - - 0 1"
    val (next, importedOpt) = transit(
      sidebarState = blankState.copy(inputText = noBlackKing),
      action = request(NotationActionId.FenImport)
    )
    importedOpt shouldBe None
    next.feedback should matchPattern {
      case Some(SidebarFeedback.Failure(_, _, FailureCategory.SemanticError)) =>
    }
  }

  it should "not call onImportedState when import fails" in {
    var called = false
    val controller = new NotationSidebarController(
      api = defaultApi,
      stateProvider = () => freshGame,
      onImportedState = _ => { called = true },
      onRefresh = _ => ()
    )
    controller.handle(SidebarAction.InputTextChanged("bad fen"))
    controller.handle(request(NotationActionId.FenImport))
    called shouldBe false
  }

  // ── PGN import ───────────────────────────────────────────────────────────────

  it should "return InvalidInput failure and None for PgnImport with empty text" in {
    // blankState has inputText = "" — PGN parsing fails with ParseFailure
    val (next, importedOpt) = transit(action = request(NotationActionId.PgnImport))
    importedOpt shouldBe None
    next.feedback should matchPattern {
      case Some(SidebarFeedback.Failure(_, _, FailureCategory.InvalidInput)) =>
    }
  }

  it should "return Some(GameState) and success feedback for a valid PGN import" in {
    val withPgn = blankState.copy(inputText = "1. e4 e5 *")
    val (next, importedOpt) =
      transit(sidebarState = withPgn, action = request(NotationActionId.PgnImport))
    importedOpt should not be empty
    importedOpt.getOrElse(fail("expected Some GameState")).currentPlayer shouldBe Color.White
    next.feedback shouldBe Some(SidebarFeedback.Success("Position imported successfully."))
  }

  // ── FEN export: success ───────────────────────────────────────────────────

  it should "populate outputText and return no importedState for FenExport" in {
    val (next, importedOpt) = transit(action = request(NotationActionId.FenExport))
    importedOpt shouldBe None
    next.outputText should not be empty
    next.feedback shouldBe None
  }

  it should "produce the standard starting-position FEN for a fresh game" in {
    val (next, _) = transit(action = request(NotationActionId.FenExport))
    next.outputText shouldBe Some("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
  }

  it should "obtain the current game state from the provider for export" in {
    var capturedState: Option[GameState] = None
    val controller = new NotationSidebarController(
      api = defaultApi,
      stateProvider = () => { capturedState = Some(freshGame); freshGame },
      onImportedState = _ => (),
      onRefresh = _ => ()
    )
    controller.handle(request(NotationActionId.FenExport))
    capturedState should not be empty
    capturedState.getOrElse(fail("expected Some GameState")) shouldBe freshGame
  }

  // ── PGN export ───────────────────────────────────────────────────────────────

  it should "populate outputText and return no feedback for PgnExport" in {
    val (next, importedOpt) = transit(action = request(NotationActionId.PgnExport))
    importedOpt shouldBe None
    next.outputText should not be empty
    next.feedback shouldBe None
  }

  it should "produce '*' result token for PgnExport of a new game" in {
    val (next, _) = transit(action = request(NotationActionId.PgnExport))
    next.outputText shouldBe Some("*")
  }

  // ── Mutable controller callbacks ───────────────────────────────────────────

  "NotationSidebarController (mutable)" should "call onImportedState with the GameState on import success" in {
    var importedState: Option[GameState] = None
    val controller = new NotationSidebarController(
      api = defaultApi,
      stateProvider = () => freshGame,
      onImportedState = s => { importedState = Some(s) },
      onRefresh = _ => ()
    )
    controller.handle(SidebarAction.InputTextChanged(InitialFen))
    controller.handle(request(NotationActionId.FenImport))
    importedState should not be empty
    importedState.getOrElse(fail("expected Some GameState")).currentPlayer shouldBe Color.White
  }

  it should "call onRefresh after every handle" in {
    var refreshCount = 0
    val controller = new NotationSidebarController(
      api = defaultApi,
      stateProvider = () => freshGame,
      onImportedState = _ => (),
      onRefresh = _ => { refreshCount += 1 }
    )
    controller.handle(SidebarAction.InputTextChanged("x"))
    controller.handle(request(NotationActionId.FenImport))
    refreshCount shouldBe 2
  }

  it should "reflect updated state via currentState after a transition" in {
    val controller = new NotationSidebarController(
      api = defaultApi,
      stateProvider = () => freshGame,
      onImportedState = _ => (),
      onRefresh = _ => ()
    )
    controller.handle(SidebarAction.InputTextChanged("hello"))
    controller.currentState.inputText shouldBe "hello"
  }

  // ── Descriptor-driven dispatch ─────────────────────────────────────────────

  "NotationSidebarController" should "route all NotationActionId values to GuiNotationApi" in {
    // Verify every id in the defaults has a route that produces a non-empty feedback
    // (success or unavailable — both are valid outcomes for configured actions).
    val controller = new NotationSidebarController(
      api = defaultApi,
      stateProvider = () => freshGame,
      onImportedState = _ => (),
      onRefresh = _ => ()
    )
    for descriptor <- NotationActionDescriptor.defaults do
      val before = controller.currentState
      controller.handle(request(descriptor.id))
      // State must have changed (feedback or imported state) — the action was handled
      val after = controller.currentState
      (after.feedback != before.feedback || after.outputText != before.outputText) shouldBe true
  }

  it should "route FenImport to api.importFen using input text" in {
    var receivedInput: Option[String] = None
    val capturingFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        receivedInput = Some(input)
        chess.notation.fen.FenParser.parse(input)
      def executeImport(
          parsed: ParsedNotation,
          target: ImportTarget
      ): Either[NotationFailure, ImportResult[GameState]] =
        chess.notation.fen.FenImporter.importNotation(parsed, target)

    val api = GuiNotationApi(capturingFacade)
    transit(
      sidebarState = blankState.copy(inputText = InitialFen),
      action = request(NotationActionId.FenImport),
      api = api
    )
    receivedInput shouldBe Some(InitialFen)
  }

  it should "route export actions with the current game state from the provider" in {
    var capturedForExport: Option[GameState] = None
    val controller = new NotationSidebarController(
      api = defaultApi,
      stateProvider = () => { capturedForExport = Some(freshGame); freshGame },
      onImportedState = _ => (),
      onRefresh = _ => ()
    )
    controller.handle(request(NotationActionId.PgnExport))
    capturedForExport should not be empty
  }

  // ── API boundary ───────────────────────────────────────────────────────────

  it should "use the injected GuiNotationApi, not notation internals" in {
    var importFenCalled = false
    val stubFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        importFenCalled = true
        chess.notation.fen.FenParser.parse(InitialFen)
      def executeImport(
          parsed: ParsedNotation,
          target: ImportTarget
      ): Either[NotationFailure, ImportResult[GameState]] =
        chess.notation.fen.FenImporter.importNotation(parsed, target)

    val api = GuiNotationApi(stubFacade)
    transit(
      sidebarState = blankState.copy(inputText = InitialFen),
      action = request(NotationActionId.FenImport),
      api = api
    )
    importFenCalled shouldBe true
  }

  // ── Defensive branches: structurally impossible outcomes ───────────────────

  "NotationSidebarController.handleImport (defensive)" should
    "return unchanged state and None when outcome is ExportSuccess" in {
      val priorState = blankState.copy(
        inputText = "some-input",
        feedback = Some(SidebarFeedback.Success("prior")),
        outputText = Some("prior-output")
      )
      val (next, imported) = NotationSidebarController
        .handleImport(priorState, GuiNotationOutcome.ExportSuccess("unexpected-text"))
      next shouldBe priorState
      imported shouldBe None
    }

  "NotationSidebarController.handleExport (defensive)" should
    "return unchanged state when outcome is ImportSuccess" in {
      val priorState = blankState.copy(
        inputText = "some-input",
        feedback = Some(SidebarFeedback.Success("prior"))
      )
      val next = NotationSidebarController
        .handleExport(priorState, GuiNotationOutcome.ImportSuccess(freshGame))
      next shouldBe priorState
    }
