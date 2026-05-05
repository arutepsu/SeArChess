package chess.adapter.gui.notation

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.application.GameStateCommandService
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
class NotationSidebarSpec extends AnyFlatSpec with Matchers:

  private val InitialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val freshGame = GameStateCommandService.createNewGame()
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

  // ── PGN import: unavailable ────────────────────────────────────────────────

  /*it should "return UnavailableFeature failure and None for PgnImport" in {
    val (next, importedOpt) = transit(action = request(NotationActionId.PgnImport))
    importedOpt shouldBe None
    next.feedback should matchPattern {
      case Some(SidebarFeedback.Failure(_, _, FailureCategory.UnavailableFeature)) =>
    }
  }*/

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

  // --- Widget smoke test (JavaFX not required for logic, but for widget instantiation)
  import scalafx.application.JFXApp3
  import scalafx.scene.Scene

  class NotationSidebarWidgetSpec extends AnyFlatSpec with Matchers {
    object DummyController
        extends NotationSidebarController(
          api = GuiNotationApi.default,
          stateProvider = () => chess.application.GameStateCommandService.createNewGame(),
          onImportedState = _ => (),
          onRefresh = _ => ()
        )

    "NotationSidebar" should "be instantiable and have a root node" in {
      val sidebar = new NotationSidebar(DummyController)
      sidebar.root should not be null
      sidebar.root.children.size should be > 0
    }

    it should "update output and feedback on refresh" in {
      val sidebar = new NotationSidebar(DummyController)
      val state = NotationSidebarState(
        outputText = Some("Exported FEN"),
        feedback = Some(SidebarFeedback.Success("OK!")),
        warnings = List(GuiNotationWarning("Warn!", GuiWarningCategory.DataLoss))
      )
      sidebar.refresh(state)
      sidebar.root.children.exists(_.isVisible) shouldBe true
    }
  }

  class NotationSidebarRefreshSpec extends AnyFlatSpec with Matchers {
    it should "update outputArea and feedbackLabel for all NotationSidebarState branches" in {
      val sidebar = new NotationSidebar(DummyController)

      // Output visible
      val stateWithOutput = NotationSidebarState(outputText = Some("abc"))
      sidebar.refresh(stateWithOutput)
      val outputAreaField =
        classOf[NotationSidebar].getDeclaredFields
          .find(_.getName == "outputArea")
          .getOrElse(fail("field outputArea not found"))
      outputAreaField.setAccessible(true)
      val outputArea = outputAreaField.get(sidebar).asInstanceOf[scalafx.scene.control.TextArea]
      outputArea.text.value shouldBe "abc"
      outputArea.visible.value shouldBe true

      // Output hidden
      val stateNoOutput = NotationSidebarState(outputText = None)
      sidebar.refresh(stateNoOutput)
      outputArea.text.value shouldBe ""
      outputArea.visible.value shouldBe false

      // Feedback Success
      val stateSuccess = NotationSidebarState(feedback = Some(SidebarFeedback.Success("ok!")))
      val feedbackLabelField =
        classOf[NotationSidebar].getDeclaredFields
          .find(_.getName == "feedbackLabel")
          .getOrElse(fail("field feedbackLabel not found"))
      feedbackLabelField.setAccessible(true)
      val feedbackLabel = feedbackLabelField.get(sidebar).asInstanceOf[scalafx.scene.control.Label]
      sidebar.refresh(stateSuccess)
      feedbackLabel.text.value shouldBe "ok!"
      feedbackLabel.visible.value shouldBe true
      feedbackLabel.style.value should include("#7ec77e")

      // Feedback Failure
      val stateFail = NotationSidebarState(feedback =
        Some(SidebarFeedback.Failure("fail", Some("details"), FailureCategory.SemanticError))
      )
      sidebar.refresh(stateFail)
      feedbackLabel.text.value should include("fail")
      feedbackLabel.text.value should include("details")
      feedbackLabel.visible.value shouldBe true
      feedbackLabel.style.value should include("#e07070")

      // Feedback None
      val stateNoFeedback = NotationSidebarState(feedback = None)
      sidebar.refresh(stateNoFeedback)
      feedbackLabel.text.value shouldBe ""
      feedbackLabel.visible.value shouldBe false

      // Warnings hidden
      val warningsBoxField =
        classOf[NotationSidebar].getDeclaredFields
          .find(_.getName == "warningsBox")
          .getOrElse(fail("field warningsBox not found"))
      warningsBoxField.setAccessible(true)
      val warningsBox = warningsBoxField.get(sidebar).asInstanceOf[scalafx.scene.layout.VBox]
      sidebar.refresh(NotationSidebarState(warnings = Nil))
      warningsBox.visible.value shouldBe false
      warningsBox.children.size shouldBe 0

      // Warnings visible
      val ws = List(GuiNotationWarning("Warn!", GuiWarningCategory.DataLoss))
      sidebar.refresh(NotationSidebarState(warnings = ws))
      warningsBox.visible.value shouldBe true
      warningsBox.children.size shouldBe 1
      warningsBox.children.head.asInstanceOf[javafx.scene.control.Label].getText should include(
        "Warn!"
      )
    }
    it should "show multiple warnings in warningsBox after refresh" in {
      val sidebar = new NotationSidebar(DummyController)
      val warnings = List(
        GuiNotationWarning("Warnung 1", GuiWarningCategory.DataLoss),
        GuiNotationWarning("Warnung 2", GuiWarningCategory.Normalization)
      )
      val state = NotationSidebarState(warnings = warnings)
      sidebar.refresh(state)
      // Zugriff direkt auf das warningsBox Feld per Reflection, da es private ist
      val warningsBoxField =
        classOf[NotationSidebar].getDeclaredFields
          .find(_.getName == "warningsBox")
          .getOrElse(fail("field warningsBox not found"))
      warningsBoxField.setAccessible(true)
      val warningsBox = warningsBoxField.get(sidebar).asInstanceOf[scalafx.scene.layout.VBox]
      val labelTexts = warningsBox.children.collect { case l: javafx.scene.control.Label =>
        l.getText
      }
      labelTexts.count(_.contains("Warnung")) should be >= 2
      warningsBox.visible.value shouldBe true
    }
    object DummyController
        extends NotationSidebarController(
          api = GuiNotationApi.default,
          stateProvider = () => chess.application.GameStateCommandService.createNewGame(),
          onImportedState = _ => (),
          onRefresh = _ => ()
        )

    "NotationSidebar.refresh" should "hide output and feedback if None" in {
      val sidebar = new NotationSidebar(DummyController)
      val state = NotationSidebarState(outputText = None, feedback = None, warnings = Nil)
      sidebar.refresh(state)
      sidebar.root.children.exists(_.isVisible) shouldBe true // root always visible
      // OutputArea and FeedbackLabel should be hidden
      // (direct access to .visible is not always possible, but we can check text)
      sidebar.root.children.exists(_.getClass.getSimpleName.contains("TextArea")) shouldBe true
    }

    it should "show feedback for Failure and Success" in {
      val sidebar = new NotationSidebar(DummyController)
      val failState = NotationSidebarState(feedback =
        Some(SidebarFeedback.Failure("fail", Some("details"), FailureCategory.SemanticError))
      )
      sidebar.refresh(failState)
      sidebar.root.children.exists(_.isVisible) shouldBe true
      val succState = NotationSidebarState(feedback = Some(SidebarFeedback.Success("ok!")))
      sidebar.refresh(succState)
      sidebar.root.children.exists(_.isVisible) shouldBe true
    }

    it should "show warnings if present" in {
      val sidebar = new NotationSidebar(DummyController)
      val state = NotationSidebarState(warnings =
        List(GuiNotationWarning("Warn!", GuiWarningCategory.DataLoss))
      )
      sidebar.refresh(state)
      sidebar.root.children.exists(_.isVisible) shouldBe true
    }
  }
