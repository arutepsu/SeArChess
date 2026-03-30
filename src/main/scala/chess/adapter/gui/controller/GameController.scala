package chess.adapter.gui.controller

import chess.adapter.gui.animation.{AnimationPlan, AnimationPlanner}
import chess.adapter.gui.input.InputAction
import chess.adapter.gui.viewmodel.*
import chess.application.{ChessService, GameState, PendingPromotion}
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
 *  @param onRefresh  called with the new [[GameViewModel]] after every state change
 *  @param onAnimate  called with an [[AnimationPlan]] when a move animation should start
 */
class GameController(
    onRefresh: GameViewModel  => Unit,
    onAnimate: AnimationPlan  => Unit
):
  private var gameState: GameState    = ChessService.createNewGame()
  private var viewModel: GameViewModel =
    GameViewModelMapper.build(gameState, GuiState.WaitingForSelection)

  def currentViewModel: GameViewModel = viewModel

  def handle(action: InputAction): Unit =
    val (newState, newVm, animPlan) = GameController.transition(gameState, viewModel, action)
    gameState = newState
    viewModel = newVm
    onRefresh(newVm)
    animPlan.foreach(onAnimate)

  /** Called by the scene when the current animation completes.
   *
   *  Transitions from [[GuiState.Animating]] to the appropriate settled state
   *  and notifies the scene via [[onRefresh]].
   */
  def completeAnimation(): Unit =
    val settled = GameController.resolveSettledGuiState(gameState)
    viewModel   = GameViewModelMapper.build(gameState, settled)
    onRefresh(viewModel)

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
          case GuiState.AwaitingPromotion => submitPromotion(state, vm, pt)
          case _                          => (state, vm, None)

  // ── Square click dispatch ──────────────────────────────────────────────────

  private def handleSquareClick(
      state: GameState,
      vm:    GameViewModel,
      pos:   Position
  ): (GameState, GameViewModel, Option[AnimationPlan]) =
    vm.guiState match
      case GuiState.GameFinished(_) | GuiState.AwaitingPromotion | GuiState.Animating =>
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
    val targets = ChessService.legalMovesFrom(state, pos)
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

  private def submitMove(
      state: GameState,
      vm:    GameViewModel,
      from:  Position,
      to:    Position
  ): (GameState, GameViewModel, Option[AnimationPlan]) =
    val prevBoard = state.board
    ChessService.applyMove(state, Move(from, to)) match
      case Left(_) =>
        // Shouldn't happen for a target from legalMovesFrom; clear selection gracefully
        clearSelection(state, vm)

      case Right(newState) if newState.pendingPromotion.isDefined =>
        val promotingColor = newState.pendingPromotion.get.color
        val promoVm = GameViewModelMapper
          .build(newState, GuiState.AwaitingPromotion)
          .copy(promotion = Some(PromotionViewModel(promotingColor, PromotionViewModel.standardChoices)))
        (newState, promoVm, None)   // no animation for promotion moves

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
      pt:    PieceType
  ): (GameState, GameViewModel, Option[AnimationPlan]) =
    ChessService.applyPromotion(state, pt) match
      case Left(_) =>
        (state, vm, None)   // invalid choice; overlay stays open
      case Right(newState) =>
        val settled = resolveSettledGuiState(newState)
        (newState, GameViewModelMapper.build(newState, settled), None)

  // ── Helpers ───────────────────────────────────────────────────────────────

  private[controller] def resolveSettledGuiState(state: GameState): GuiState =
    state.status match
      case GameStatus.Checkmate | GameStatus.Stalemate => GuiState.GameFinished(state.status)
      case _                                           => GuiState.WaitingForSelection
