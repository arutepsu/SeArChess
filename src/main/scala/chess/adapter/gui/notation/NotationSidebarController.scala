package chess.adapter.gui.notation

import chess.domain.state.GameState

/** Mutable controller for the notation sidebar.
 *
 *  Follows the same pattern as [[chess.adapter.gui.controller.GameController]]:
 *  - the companion object contains a pure `transition` function (testable, no JavaFX)
 *  - this class wraps it with mutable local state and fires GUI callbacks
 *
 *  Responsibilities:
 *  - route [[SidebarAction]]s to the pure transition
 *  - emit the imported [[GameState]] upward via [[onImportedState]] on import success
 *  - notify the widget of state changes via [[onRefresh]]
 *
 *  Does NOT:
 *  - own authoritative application state
 *  - mutate the active scene or game controller directly
 *
 *  @param api             the GUI-facing notation API
 *  @param stateProvider   callback to obtain the current game state for export actions
 *  @param onImportedState called with the imported [[GameState]] when an import succeeds
 *  @param onRefresh       called after every transition with the new sidebar state
 */
class NotationSidebarController(
    api:             GuiNotationApi,
    stateProvider:   () => GameState,
    onImportedState: GameState => Unit,
    onRefresh:       NotationSidebarState => Unit
):
  private var sidebarState: NotationSidebarState = NotationSidebarState()

  def currentState: NotationSidebarState = sidebarState

  def handle(action: SidebarAction): Unit =
    val currentGame              = stateProvider()
    val (next, importedStateOpt) = NotationSidebarController.transition(sidebarState, action, api, currentGame)
    sidebarState = next
    importedStateOpt.foreach(onImportedState)
    onRefresh(sidebarState)

// ── Companion: pure transition logic ──────────────────────────────────────────

object NotationSidebarController:

  /** Pure transition from one [[NotationSidebarState]] to the next.
   *
   *  @param state        the current sidebar state
   *  @param action       the action to process
   *  @param api          the GUI-facing notation API
   *  @param currentGame  the current game state, provided by the caller for export
   *  @return (next sidebar state, Some(importedGameState) if an import succeeded)
   */
  def transition(
      state:       NotationSidebarState,
      action:      SidebarAction,
      api:         GuiNotationApi,
      currentGame: GameState
  ): (NotationSidebarState, Option[GameState]) =
    action match

      case SidebarAction.InputTextChanged(text) =>
        (state.copy(inputText = text), None)

      case SidebarAction.NotationActionRequested(id) =>
        dispatch(state, id, api, currentGame)

  // ── Action dispatch ──────────────────────────────────────────────────────────

  /** Map a [[NotationActionId]] to the appropriate [[GuiNotationApi]] call and handler.
   *
   *  This is the single place where action identifiers are resolved to API methods.
   *  Adding a new notation action requires only extending this match and registering
   *  a descriptor in [[NotationActionDescriptor.defaults]].
   */
  private def dispatch(
      state:       NotationSidebarState,
      id:          NotationActionId,
      api:         GuiNotationApi,
      currentGame: GameState
  ): (NotationSidebarState, Option[GameState]) =
    id match
      case NotationActionId.FenImport => handleImport(state, api.importFen(state.inputText))
      case NotationActionId.PgnImport => handleImport(state, api.importPgn(state.inputText))
      case NotationActionId.FenExport => (handleExport(state, api.exportFen(currentGame)), None)
      case NotationActionId.PgnExport => (handleExport(state, api.exportPgn(currentGame)), None)

  // ── Import outcome handling ────────────────────────────────────────────────

  /** Map an import outcome to the next state and an optional imported [[GameState]].
   *
   *  On success: update feedback, preserve warnings, signal the imported state.
   *  On failure: update feedback, clear warnings and output, do not signal.
   */
  private[notation] def handleImport(
      state:   NotationSidebarState,
      outcome: GuiNotationOutcome
  ): (NotationSidebarState, Option[GameState]) =
    outcome match
      case GuiNotationOutcome.ImportSuccess(importedState, warnings) =>
        val next = state.copy(
          feedback   = Some(SidebarFeedback.Success("Position imported successfully.")),
          warnings   = warnings,
          outputText = None
        )
        (next, Some(importedState))

      case GuiNotationOutcome.Failure(message, details, category) =>
        val next = state.copy(
          feedback   = Some(SidebarFeedback.Failure(message, details, category)),
          warnings   = Nil,
          outputText = None
        )
        (next, None)

      case GuiNotationOutcome.ExportSuccess(_) =>
        // ExportSuccess from an import call is structurally impossible; treat defensively.
        (state, None)

  // ── Export outcome handling ────────────────────────────────────────────────

  /** Map an export outcome to the next state.
   *
   *  On success: populate output text, clear feedback.
   *  On failure: populate feedback with readable message, clear output text.
   */
  private[notation] def handleExport(
      state:   NotationSidebarState,
      outcome: GuiNotationOutcome
  ): NotationSidebarState =
    outcome match
      case GuiNotationOutcome.ExportSuccess(text) =>
        state.copy(
          outputText = Some(text),
          feedback   = None,
          warnings   = Nil
        )

      case GuiNotationOutcome.Failure(message, details, category) =>
        state.copy(
          outputText = None,
          feedback   = Some(SidebarFeedback.Failure(message, details, category)),
          warnings   = Nil
        )

      case GuiNotationOutcome.ImportSuccess(_, _) =>
        // ImportSuccess from an export call is structurally impossible; treat defensively.
        state
