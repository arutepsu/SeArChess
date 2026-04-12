package chess.adapter.gui.controller

import chess.adapter.gui.animation.{AnimationPlan, AnimationPlanner}
import chess.adapter.gui.input.InputAction
import chess.adapter.gui.viewmodel.*
import chess.application.ChessService
import chess.application.session.model.{GameSession, SessionLifecycle, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.application.session.service.SessionGameService
import chess.domain.state.GameState
import chess.domain.model.{Color, GameStatus, Move, PieceType, Position}

/** Mutable GUI controller.
 *
 *  Holds the current game and view state.  On every input event it delegates to
 *  the pure [[GameController.transition]] function, stores the result, and fires
 *  the appropriate callback.
 *
 *  Animation is decoupled: [[onAnimate]] is called when a move should be animated;
 *  [[onRefresh]] is called whenever the view model changes.  When the animation
 *  runner finishes, the scene calls [[completeAnimation]] to settle the GUI state.
 *
 *  === Session-aware mode ===
 *  When `sessionGameService` and `initialSession` are supplied, move submission is
 *  routed through [[SessionGameService.submitMove]] — the single application mutation
 *  boundary — instead of [[ChessService.applyMove]] directly.  That boundary
 *  validates the move, publishes application events, persists session lifecycle, and
 *  persists the new [[chess.domain.state.GameState]].  Session lifecycle is tracked
 *  automatically:
 *  - first move transitions [[chess.application.session.model.SessionLifecycle.Created]] → Active
 *  - promotion detection transitions Active → AwaitingPromotion before the overlay opens
 *  - promotion completion transitions AwaitingPromotion → Active (or Finished if checkmate)
 *  - terminal game positions transition any lifecycle → Finished
 *
 *  When neither parameter is supplied (default), behavior falls back to the pure
 *  [[chess.application.ChessService.applyMove]] path (no persistence, no events).
 *
 *  @param onRefresh          called with the new [[GameViewModel]] after every state change
 *  @param onAnimate          called with an [[AnimationPlan]] when a move animation should start
 *  @param sessionGameService optional unified application mutation boundary
 *  @param initialSession     optional starting [[GameSession]]; must already be persisted
 *                            in the repository backing [[sessionGameService]]
 */
class GameController(
    game:               chess.application.ObservableGame,
    onRefresh:          GameViewModel     => Unit,
    onAnimate:          AnimationPlan     => Unit,
    sessionGameService: Option[SessionGameService] = None,
    initialSession:     Option[GameSession]        = None
):
  private var gameState: GameState     = game.getState
  private var viewModel: GameViewModel =
    GameViewModelMapper.build(gameState, GuiState.WaitingForSelection)

  /** Tracks the current session when operating in session-aware mode. */
  private var currentSession: Option[GameSession] = initialSession

  // Listen to external state changes (e.g., from TUI)
  game.addObserver { newState =>
    val action = () => {
      if newState != gameState then
        gameState = newState
        val settled = GameController.resolveSettledGuiState(newState)
        viewModel = GameViewModelMapper.build(newState, settled)
        onRefresh(viewModel)
    }
    try
      scalafx.application.Platform.runLater { action() }
    catch
      case _: IllegalStateException => action() // Fallback when FX Toolkit isn't initialized (e.g. tests)
  }

  def currentViewModel: GameViewModel = viewModel

  /** The current authoritative [[GameState]].
   *
   *  Used by the notation sidebar's state provider so that export actions
   *  always reflect the live game state.
   */
  def currentGameState: GameState = gameState

  def handle(action: InputAction): Unit =
    val (newState, newVm, animPlan) =
      if sessionGameService.isDefined && currentSession.isDefined then
        sessionAwareHandle(action)
      else
        GameController.transition(gameState, viewModel, action)
    commitTransition(newState, newVm, animPlan)

  /** Replace the current game state with an externally imported one.
   *
   *  Intended for notation import (FEN now, PGN later): the sidebar emits an
   *  imported [[GameState]] upward and the scene calls this method to apply it.
   *
   *  Behaviour:
   *  - assigns the imported state as the authoritative state
   *  - resolves the settled [[GuiState]] (e.g. [[GuiState.WaitingForSelection]]
   *    or [[GuiState.GameFinished]]) using the same logic as animation completion
   *  - rebuilds [[viewModel]] from the imported state
   *  - calls [[onRefresh]] with the new view model
   *
   *  Does NOT trigger animation.
   *  Does NOT preserve stale selection, promotion, or animating UI state.
   *  Does NOT treat the loaded state as a gameplay input.
   *
   *  === Session policy for imports ===
   *  An imported position is treated as a '''new session''' aligned to the
   *  imported state's terminal/ongoing status:
   *  - terminal (checkmate/draw) → [[chess.application.session.model.SessionLifecycle.Finished]]
   *  - non-terminal             → [[chess.application.session.model.SessionLifecycle.Active]]
   *
   *  [[chess.application.session.model.SessionLifecycle.Created]] is intentionally skipped:
   *  an imported board position is already "in progress", not awaiting a first move.
   *  Best-effort: session provisioning failure leaves `currentSession` stale but
   *  chess gameplay continues correctly.
   */
  def loadGameState(importedState: GameState): Unit =
    // Session-aware: provision a fresh session matching the imported state's lifecycle.
    for
      service <- sessionGameService
      session <- currentSession
    do
      val targetLifecycle = importedState.status match
        case _: GameStatus.Checkmate | _: GameStatus.Draw => SessionLifecycle.Finished
        case _                                            => SessionLifecycle.Active
      service
        .createSession(GameId.random(), session.mode, session.whiteController, session.blackController)
        .flatMap(newSess => service.updateLifecycle(newSess.sessionId, targetLifecycle))
        .foreach(updated => currentSession = Some(updated))

    gameState = importedState
    val settled = GameController.resolveSettledGuiState(gameState)
    viewModel   = GameViewModelMapper.build(gameState, settled)
    onRefresh(viewModel)

  /** Called by the scene when the current animation completes.
   *
   *  Transitions from [[GuiState.Animating]] to the appropriate settled state
   *  and notifies the scene via [[onRefresh]].
   */
  def completeAnimation(): Unit =
    val settled = GameController.resolveSettledGuiState(gameState)
    viewModel   = GameViewModelMapper.build(gameState, settled)
    onRefresh(viewModel)

  // ── Session-aware move handling ─────────────────────────────────────────────

  /** Routes move-submission actions through [[SessionGameService.submitMove]] — the
   *  single application mutation boundary — when session context is present.
   *  Selection, reset, and all non-move actions fall through to the standard pure
   *  [[GameController.transition]] path.
   *
   *  Promotion flow:
   *  1. When a pawn reaches the back rank ([[ChessService.isPromotionPending]]),
   *     the session is transitioned to [[chess.application.session.model.SessionLifecycle.AwaitingPromotion]]
   *     before the promotion overlay is shown.  No domain state change occurs yet.
   *  2. When the promotion piece is chosen, [[SessionGameService.submitMove]] commits
   *     the complete move (with promotion) and transitions the session back to Active
   *     (or Finished if the game ends).
   */
  private def sessionAwareHandle(action: InputAction): (GameState, GameViewModel, Option[AnimationPlan]) =
    val service = sessionGameService.get
    val session = currentSession.get

    action match
      case InputAction.SquareClicked(pos) =>
        viewModel.guiState match
          case GuiState.PieceSelected(from, targets) if targets.contains(pos) =>
            if ChessService.isPromotionPending(gameState, from, pos) then
              // Transition session to AwaitingPromotion before opening the overlay.
              // Best-effort: overlay still opens even if session persistence fails.
              service.preparePromotion(session.sessionId) match
                case Right(updated) => currentSession = Some(updated)
                case Left(_)        => ()
              val promotingColor = gameState.board.pieceAt(from).get.color
              val gs = GuiState.AwaitingPromotion(from, pos)
              val promoVm = GameViewModelMapper.build(gameState, gs)
                .copy(promotion = Some(PromotionViewModel(promotingColor, PromotionViewModel.standardChoices)))
              (gameState, promoVm, None)
            else
              // Regular move through the unified application mutation boundary.
              // submitMove validates, applies, publishes events, and persists both
              // the session lifecycle and the new GameState before returning.
              service.submitMove(session, gameState, Move(from, pos), SideController.HumanLocal) match
                case Left(_) =>
                  // Illegal move (shouldn't happen for targets from legalTargetsFrom); clear selection.
                  val gs = GuiState.WaitingForSelection
                  (gameState, viewModel.copy(
                    squares  = GameViewModelMapper.buildSquares(gameState, gs),
                    guiState = gs), None)
                case Right((newState, newSess)) =>
                  currentSession = Some(newSess)
                  AnimationPlanner.plan(gameState.board, Move(from, pos)) match
                    case Some(p) =>
                      (newState, GameViewModelMapper.build(newState, GuiState.Animating), Some(p))
                    case None =>
                      val settled = GameController.resolveSettledGuiState(newState)
                      (newState, GameViewModelMapper.build(newState, settled), None)

          case _ =>
            // Not a move confirmation (selection, clicking off, etc.) — use pure path.
            GameController.transition(gameState, viewModel, action)

      case InputAction.PromotionPieceChosen(pt) =>
        viewModel.guiState match
          case GuiState.AwaitingPromotion(from, to) =>
            // Commit the complete promotion move through the unified application boundary.
            // Session lifecycle: AwaitingPromotion → Active (or Finished).
            service.submitMove(session, gameState, Move(from, to, Some(pt)), SideController.HumanLocal) match
              case Left(_) =>
                (gameState, viewModel, None)  // invalid choice; overlay stays open
              case Right((newState, newSess)) =>
                currentSession = Some(newSess)
                val settled = GameController.resolveSettledGuiState(newState)
                (newState, GameViewModelMapper.build(newState, settled), None)
          case _ =>
            (gameState, viewModel, None)

      case InputAction.ResetClicked =>
        // Provision a fresh session and persist the initial game state atomically.
        // Best-effort: if newGame fails, fall back to the pure domain reset so
        // gameplay continues correctly; currentSession becomes stale in that case.
        service.newGame(session.mode, session.whiteController, session.blackController) match
          case Right((fresh, newSess)) =>
            currentSession = Some(newSess)
            (fresh, GameViewModelMapper.build(fresh, GuiState.WaitingForSelection), None)
          case Left(_) =>
            val fresh = ChessService.createNewGame()
            (fresh, GameViewModelMapper.build(fresh, GuiState.WaitingForSelection), None)

      case _ =>
        // Remaining non-move actions (selection, etc.) use the pure path.
        GameController.transition(gameState, viewModel, action)

  private def commitTransition(
    newState: GameState,
    newVm:    GameViewModel,
    animPlan: Option[AnimationPlan]
  ): Unit =
    val stateChanged = newState != gameState
    gameState = newState
    viewModel = newVm
    onRefresh(newVm)
    animPlan.foreach(onAnimate)
    if stateChanged then game.updateState(newState)

// ── Companion: pure transition logic ─────────────────────────────────────────

object GameController:

  // Return type: (new game state, new view model, optional animation plan)
  def transition(
      state:  GameState,
      vm:     GameViewModel,
      action: InputAction
  ): (GameState, GameViewModel, Option[AnimationPlan]) =
    action match
      case InputAction.ResetClicked =>
        val fresh = ChessService.createNewGame()
        (fresh, GameViewModelMapper.build(fresh, GuiState.WaitingForSelection), None)

      case InputAction.SquareClicked(pos) =>
        handleSquareClick(state, vm, pos)

      case InputAction.PromotionPieceChosen(pt) =>
        vm.guiState match
          case GuiState.AwaitingPromotion(from, to) => submitPromotion(state, vm, from, to, pt)
          case _                                    => (state, vm, None)

  // ── Square click dispatch ──────────────────────────────────────────────────

  private def handleSquareClick(
      state: GameState,
      vm:    GameViewModel,
      pos:   Position
  ): (GameState, GameViewModel, Option[AnimationPlan]) =
    vm.guiState match
      case GuiState.GameFinished(_) | GuiState.AwaitingPromotion(_, _) | GuiState.Animating =>
        (state, vm, None)   // input blocked

      case GuiState.PieceSelected(from, targets) =>
        if pos == from then
          clearSelection(state, vm)
        else if targets.contains(pos) then
          submitMove(state, vm, from, pos)
        else
          attemptSelection(state, vm, pos)

      case GuiState.WaitingForSelection =>
        attemptSelection(state, vm, pos)

  // ── Selection ──────────────────────────────────────────────────────────────

  private def attemptSelection(
      state: GameState,
      vm:    GameViewModel,
      pos:   Position
  ): (GameState, GameViewModel, Option[AnimationPlan]) =
    val targets = ChessService.legalTargetsFrom(state, pos)
    if targets.nonEmpty then
      val gs = GuiState.PieceSelected(pos, targets)
      (state, vm.copy(squares = GameViewModelMapper.buildSquares(state, gs), guiState = gs), None)
    else
      clearSelection(state, vm)

  private def clearSelection(
      state: GameState,
      vm:    GameViewModel
  ): (GameState, GameViewModel, Option[AnimationPlan]) =
    val gs = GuiState.WaitingForSelection
    (state, vm.copy(squares = GameViewModelMapper.buildSquares(state, gs), guiState = gs), None)

  // ── Move submission ────────────────────────────────────────────────────────

  private def isPromotionMove(state: GameState, from: Position, to: Position): Boolean =
    ChessService.isPromotionPending(state, from, to)

  private def submitMove(
      state: GameState,
      vm:    GameViewModel,
      from:  Position,
      to:    Position
  ): (GameState, GameViewModel, Option[AnimationPlan]) =
    if isPromotionMove(state, from, to) then
      // Do not submit yet — wait for piece choice
      val promotingColor = state.board.pieceAt(from).get.color
      val gs      = GuiState.AwaitingPromotion(from, to)
      val promoVm = GameViewModelMapper
        .build(state, gs)
        .copy(promotion = Some(PromotionViewModel(promotingColor, PromotionViewModel.standardChoices)))
      (state, promoVm, None)
    else
      val prevBoard = state.board
      ChessService.applyMove(state, Move(from, to)) match
        case Left(_) =>
          // Shouldn't happen for a target from legalMovesFrom; clear selection gracefully
          clearSelection(state, vm)

        case Right(newState) =>
          // Try to build an animation plan; castling returns None (not animated yet).
          val plan = AnimationPlanner.plan(prevBoard, Move(from, to))
          plan match
            case Some(p) =>
              val animVm = GameViewModelMapper.build(newState, GuiState.Animating)
              (newState, animVm, Some(p))
            case None =>
              // Castling or defensive fallback: settle immediately
              val settled = resolveSettledGuiState(newState)
              (newState, GameViewModelMapper.build(newState, settled), None)

  // ── Promotion submission ───────────────────────────────────────────────────

  private def submitPromotion(
      state: GameState,
      vm:    GameViewModel,
      from:  Position,
      to:    Position,
      pt:    PieceType
  ): (GameState, GameViewModel, Option[AnimationPlan]) =
    ChessService.applyMove(state, Move(from, to, Some(pt))) match
      case Left(_) =>
        (state, vm, None)   // invalid choice; overlay stays open
      case Right(newState) =>
        val settled = resolveSettledGuiState(newState)
        (newState, GameViewModelMapper.build(newState, settled), None)

  // ── Helpers ───────────────────────────────────────────────────────────────

  private[controller] def resolveSettledGuiState(state: GameState): GuiState =
    state.status match
      case _: GameStatus.Checkmate | _: GameStatus.Draw => GuiState.GameFinished(state.status)
      case _                                            => GuiState.WaitingForSelection
