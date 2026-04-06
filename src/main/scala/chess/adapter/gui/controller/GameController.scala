package chess.adapter.gui.controller

import chess.adapter.gui.animation.{AnimationPlan, AnimationPlanner}
import chess.adapter.gui.input.InputAction
import chess.adapter.gui.viewmodel.*
import chess.application.ChessService
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

  /** The current authoritative [[GameState]].
   *
   *  Used by the notation sidebar's state provider so that export actions
   *  always reflect the live game state.
   */
  def currentGameState: GameState = gameState

  def handle(action: InputAction): Unit =
    val (newState, newVm, animPlan) = GameController.transition(gameState, viewModel, action)
    gameState = newState
    viewModel = newVm
    onRefresh(newVm)
    animPlan.foreach(onAnimate)

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
   */
  def loadGameState(importedState: GameState): Unit =
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
    state.board.pieceAt(from).exists { piece =>
      piece.pieceType == PieceType.Pawn && piece.color == state.currentPlayer &&
        ((piece.color == Color.White && to.rank == 7) ||
         (piece.color == Color.Black && to.rank == 0))
    }

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
