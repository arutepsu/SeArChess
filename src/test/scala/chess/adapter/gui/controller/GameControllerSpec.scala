package chess.adapter.gui.controller

import chess.adapter.gui.animation.AnimationPlan
import chess.adapter.gui.input.InputAction
import chess.adapter.gui.viewmodel.{GameViewModel, GameViewModelMapper, GuiState, PromotionViewModel}
import chess.application.ChessService
import chess.domain.state.GameState
import chess.domain.model.{Board, Color, DrawReason, GameStatus, Move, Piece, PieceType, Position}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GameControllerSpec extends AnyFlatSpec with Matchers with EitherValues:

  // ── helpers ────────────────────────────────────────────────────────────────

  private def pos(file: Int, rank: Int): Position =
    Position.from(file, rank).getOrElse(throw AssertionError(s"Bad pos: $file,$rank"))

  private def algPos(alg: String): Position =
    Position.fromAlgebraic(alg).getOrElse(throw AssertionError(s"Bad algebraic: $alg"))

  private def freshState: GameState    = ChessService.createNewGame()
  private def freshVm(s: GameState): GameViewModel =
    GameViewModelMapper.build(s, GuiState.WaitingForSelection)

  // ── ResetClicked ───────────────────────────────────────────────────────────

  "GameController.transition" should "reset to a fresh game on ResetClicked" in {
    val state = freshState
    val (newState, newVm, plan) = GameController.transition(state, freshVm(state), InputAction.ResetClicked)
    newState.currentPlayer shouldBe Color.White
    newState.board         shouldBe Board.initial
    newVm.guiState         shouldBe GuiState.WaitingForSelection
    plan                   shouldBe None
  }

  // ── SquareClicked: WaitingForSelection ────────────────────────────────────

  it should "select a piece with legal moves on SquareClicked from WaitingForSelection" in {
    val state = freshState
    val e2    = algPos("e2")
    val (_, newVm, plan) = GameController.transition(state, freshVm(state), InputAction.SquareClicked(e2))
    newVm.guiState match
      case GuiState.PieceSelected(from, targets) =>
        from    shouldBe e2
        targets should not be empty
      case other => fail(s"Expected PieceSelected, got $other")
    plan shouldBe None
  }

  it should "stay WaitingForSelection when clicking an empty square" in {
    val state = freshState
    val (_, newVm, plan) = GameController.transition(state, freshVm(state), InputAction.SquareClicked(algPos("e4")))
    newVm.guiState shouldBe GuiState.WaitingForSelection
    plan           shouldBe None
  }

  it should "stay WaitingForSelection when clicking an opponent's piece with no own piece selected" in {
    val state = freshState   // White to move; e7 = Black pawn
    val (_, newVm, plan) = GameController.transition(state, freshVm(state), InputAction.SquareClicked(algPos("e7")))
    newVm.guiState shouldBe GuiState.WaitingForSelection
    plan           shouldBe None
  }

  // ── SquareClicked: PieceSelected ──────────────────────────────────────────

  it should "deselect when clicking the already-selected square" in {
    val state = freshState
    val e2    = algPos("e2")
    val (_, vm1, _) = GameController.transition(state, freshVm(state), InputAction.SquareClicked(e2))
    vm1.guiState shouldBe a[GuiState.PieceSelected]
    val (_, vm2, plan) = GameController.transition(state, vm1, InputAction.SquareClicked(e2))
    vm2.guiState shouldBe GuiState.WaitingForSelection
    plan         shouldBe None
  }

  it should "re-select another own piece from PieceSelected without animation" in {
    val state = freshState
    val (_, vm1, _) = GameController.transition(state, freshVm(state), InputAction.SquareClicked(algPos("e2")))
    val (_, vm2, plan) = GameController.transition(state, vm1, InputAction.SquareClicked(algPos("d2")))
    vm2.guiState match
      case GuiState.PieceSelected(from, _) => from shouldBe algPos("d2")
      case other => fail(s"Expected PieceSelected(d2, _), got $other")
    plan shouldBe None
  }

  // ── Move execution and animation ──────────────────────────────────────────

  it should "transition to Animating and produce a plan on a legal move" in {
    val state = freshState
    val e2    = algPos("e2")
    val e4    = algPos("e4")
    val (_, vm1, _) = GameController.transition(state, freshVm(state), InputAction.SquareClicked(e2))
    val (newState, newVm, plan) = GameController.transition(state, vm1, InputAction.SquareClicked(e4))
    newVm.guiState         shouldBe GuiState.Animating
    newState.currentPlayer shouldBe Color.Black
    newState.board.pieceAt(e4) shouldBe Some(Piece(Color.White, PieceType.Pawn))
    newState.board.pieceAt(e2) shouldBe None
    plan                   shouldBe defined
    plan.get.movingPiece   shouldBe (Color.White, PieceType.Pawn)
    plan.get.from          shouldBe e2
    plan.get.to            shouldBe e4
  }

  it should "produce a capture animation plan with the captured piece" in {
    // Set up a white pawn at e5 and a black pawn at d6 so e5→d6 is a capture
    val e5 = algPos("e5")
    val d6 = algPos("d6")
    val e1 = algPos("e1")
    val e8 = algPos("e8")
    val board = Board.empty
      .place(e5, Piece(Color.White, PieceType.Pawn))
      .place(d6, Piece(Color.Black, PieceType.Pawn))
      .place(e1, Piece(Color.White, PieceType.King))
      .place(e8, Piece(Color.Black, PieceType.King))
    val state = freshState.copy(board = board)
    val vm    = freshVm(state)
    val (_, vm1, _)   = GameController.transition(state, vm, InputAction.SquareClicked(e5))
    val (_, _, plan)  = GameController.transition(state, vm1, InputAction.SquareClicked(d6))
    plan shouldBe defined
    plan.get.isCapture     shouldBe true
    plan.get.capturedPiece shouldBe Some((Color.Black, PieceType.Pawn))
  }

  // ── Promotion flow ────────────────────────────────────────────────────────

  it should "transition to AwaitingPromotion (no animation) when a pawn reaches the last rank" in {
    val a7 = pos(0, 6)
    val a8 = pos(0, 7)
    val state = freshState.copy(board = Board.empty.place(a7, Piece(Color.White, PieceType.Pawn)))
    val (_, vm1, _) = GameController.transition(state, freshVm(state), InputAction.SquareClicked(a7))
    val (_, vm2, plan) = GameController.transition(state, vm1, InputAction.SquareClicked(a8))
    vm2.guiState match
      case GuiState.AwaitingPromotion(from, to) =>
        from shouldBe a7
        to   shouldBe a8
      case other => fail(s"Expected AwaitingPromotion, got $other")
    vm2.promotion shouldBe defined
    plan          shouldBe None   // promotion moves are not animated
  }

  it should "complete promotion and return to WaitingForSelection" in {
    val a8 = pos(0, 7)
    val a7 = pos(0, 6)
    val e1 = algPos("e1")
    val e8 = algPos("e8")
    // Set up state with pawn at a7 ready to promote; king positions for legality
    val state = freshState.copy(
      board = Board.empty
        .place(a7, Piece(Color.White, PieceType.Pawn))
        .place(e1, Piece(Color.White, PieceType.King))
        .place(e8, Piece(Color.Black, PieceType.King))
    )
    val vm = GameViewModelMapper.build(state, GuiState.AwaitingPromotion(a7, a8)).copy(
      promotion = Some(PromotionViewModel(Color.White, PromotionViewModel.standardChoices))
    )
    val (newState, newVm, plan) = GameController.transition(state, vm, InputAction.PromotionPieceChosen(PieceType.Queen))
    newVm.guiState             shouldBe GuiState.WaitingForSelection
    newState.currentPlayer     shouldBe Color.Black
    newState.board.pieceAt(a8) shouldBe Some(Piece(Color.White, PieceType.Queen))
    plan                       shouldBe None
  }

  it should "transition to AwaitingPromotion when a Black pawn reaches rank 1" in {
    val a2 = pos(0, 1)
    val a1 = pos(0, 0)
    val state = freshState.copy(
      board         = Board.empty.place(a2, Piece(Color.Black, PieceType.Pawn)),
      currentPlayer = Color.Black
    )
    val (_, vm1, _)    = GameController.transition(state, freshVm(state), InputAction.SquareClicked(a2))
    val (_, vm2, plan) = GameController.transition(state, vm1, InputAction.SquareClicked(a1))
    vm2.guiState match
      case GuiState.AwaitingPromotion(from, to) =>
        from shouldBe a2
        to   shouldBe a1
      case other => fail(s"Expected AwaitingPromotion, got $other")
    plan shouldBe None
  }

  it should "ignore PromotionPieceChosen when not in AwaitingPromotion state" in {
    val state = freshState
    val (s2, vm2, plan) = GameController.transition(state, freshVm(state), InputAction.PromotionPieceChosen(PieceType.Queen))
    vm2.guiState       shouldBe GuiState.WaitingForSelection
    s2.currentPlayer   shouldBe Color.White
    plan               shouldBe None
  }

  // ── Input blocked in terminal/overlay/animating states ───────────────────

  it should "ignore SquareClicked when in Animating state and produce no plan" in {
    val state = freshState
    val vm    = GameViewModelMapper.build(state, GuiState.Animating)
    val (s2, vm2, plan) = GameController.transition(state, vm, InputAction.SquareClicked(algPos("e2")))
    vm2.guiState shouldBe GuiState.Animating
    s2           shouldBe state
    plan         shouldBe None
  }

  it should "ignore SquareClicked when in AwaitingPromotion state" in {
    val a8 = pos(0, 7)
    val a7 = pos(0, 6)
    val state = freshState.copy(
      board = Board.empty.place(a7, Piece(Color.White, PieceType.Pawn))
    )
    val vm = GameViewModelMapper.build(state, GuiState.AwaitingPromotion(a7, a8))
    val (s2, vm2, _) = GameController.transition(state, vm, InputAction.SquareClicked(a8))
    vm2.guiState match
      case GuiState.AwaitingPromotion(_, _) => // expected
      case other => fail(s"Expected AwaitingPromotion, got $other")
    s2 shouldBe state
  }

  it should "ignore SquareClicked when in GameFinished state" in {
    val state = freshState
    val vm    = GameViewModelMapper.build(state, GuiState.GameFinished(GameStatus.Checkmate(Color.White)))
    val (s2, vm2, _) = GameController.transition(state, vm, InputAction.SquareClicked(algPos("e2")))
    vm2.guiState shouldBe GuiState.GameFinished(GameStatus.Checkmate(Color.White))
    s2           shouldBe state
  }

  // ── Animation completion (via resolveSettledGuiState) ────────────────────

  it should "transition through Animating to GameFinished(Checkmate) after a mating move" in {
    // Back-rank mate: White Rook b8→a8 checkmates Black King at h8
    val b8 = algPos("b8")
    val a8 = algPos("a8")
    val g6 = algPos("g6")
    val h8 = algPos("h8")
    val a1 = algPos("a1")
    val board = Board.empty
      .place(a1, Piece(Color.White, PieceType.King))
      .place(g6, Piece(Color.White, PieceType.Queen))
      .place(b8, Piece(Color.White, PieceType.Rook))
      .place(h8, Piece(Color.Black, PieceType.King))
    val state = freshState.copy(board = board)
    val (_, vmSel, _) = GameController.transition(state, freshVm(state), InputAction.SquareClicked(b8))
    val (matedState, animVm, plan) = GameController.transition(state, vmSel, InputAction.SquareClicked(a8))
    animVm.guiState    shouldBe GuiState.Animating
    matedState.status  shouldBe GameStatus.Checkmate(Color.White)
    plan               shouldBe defined
  }

  // ── submitMove Left branch ────────────────────────────────────────────────

  it should "fall back to WaitingForSelection when submitMove receives a rejected move" in {
    val state = freshState.copy(board = Board.empty)
    val e2    = algPos("e2")
    val e4    = algPos("e4")
    val vm = freshVm(state).copy(
      guiState = GuiState.PieceSelected(e2, Set(e4)),
      squares  = GameViewModelMapper.buildSquares(state, GuiState.PieceSelected(e2, Set(e4)))
    )
    val (_, newVm, plan) = GameController.transition(state, vm, InputAction.SquareClicked(e4))
    newVm.guiState shouldBe GuiState.WaitingForSelection
    plan           shouldBe None
  }

  // ── submitPromotion Left branch ───────────────────────────────────────────

  it should "keep AwaitingPromotion state when submitPromotion returns Left" in {
    val a8 = pos(0, 7)
    val a7 = pos(0, 6)
    // State with no pawn at a7 — promotion move will fail validation
    val state = freshState
    val vm    = GameViewModelMapper.build(state, GuiState.AwaitingPromotion(a7, a8))
    val (s2, vm2, plan) = GameController.transition(state, vm, InputAction.PromotionPieceChosen(PieceType.Queen))
    vm2.guiState match
      case GuiState.AwaitingPromotion(_, _) => // expected
      case other => fail(s"Expected AwaitingPromotion, got $other")
    s2   shouldBe state
    plan shouldBe None
  }

  // ── GameController class (mutable wrapper) ────────────────────────────────

  "GameController class" should "expose the initial view model via currentViewModel" in {
    val ctrl = new GameController(_ => (), _ => ())
    ctrl.currentViewModel.guiState shouldBe GuiState.WaitingForSelection
    ctrl.currentViewModel.squares  should have size 64
  }

  it should "call onRefresh and onAnimate after a move" in {
    var refreshCount = 0
    var capturedPlan: Option[AnimationPlan] = None
    val ctrl = new GameController(
      _ => refreshCount += 1,
      p => capturedPlan = Some(p)
    )
    val e2 = algPos("e2")
    val e4 = algPos("e4")
    ctrl.handle(InputAction.SquareClicked(e2))   // selection — refresh only
    ctrl.handle(InputAction.SquareClicked(e4))   // move — refresh + animate
    refreshCount        shouldBe 2
    capturedPlan        shouldBe defined
    capturedPlan.get.to shouldBe e4
  }

  it should "settle to WaitingForSelection after completeAnimation" in {
    var lastVm: Option[GameViewModel] = None
    val ctrl = new GameController(vm => lastVm = Some(vm), _ => ())
    ctrl.handle(InputAction.SquareClicked(algPos("e2")))
    ctrl.handle(InputAction.SquareClicked(algPos("e4")))
    ctrl.currentViewModel.guiState shouldBe GuiState.Animating
    ctrl.completeAnimation()
    ctrl.currentViewModel.guiState shouldBe GuiState.WaitingForSelection
    lastVm.get.guiState            shouldBe GuiState.WaitingForSelection
  }

  it should "settle to GameFinished after completeAnimation on a checkmate move" in {
    val b8 = algPos("b8")
    val a8 = algPos("a8")
    val g6 = algPos("g6")
    val h8 = algPos("h8")
    val a1 = algPos("a1")
    val board = Board.empty
      .place(a1, Piece(Color.White, PieceType.King))
      .place(g6, Piece(Color.White, PieceType.Queen))
      .place(b8, Piece(Color.White, PieceType.Rook))
      .place(h8, Piece(Color.Black, PieceType.King))
    val state  = freshState.copy(board = board)
    val (_, vmSel, _) = GameController.transition(state, freshVm(state), InputAction.SquareClicked(b8))
    val (matedState, animVm, _) = GameController.transition(state, vmSel, InputAction.SquareClicked(a8))
    animVm.guiState   shouldBe GuiState.Animating
    val settled = GameController.resolveSettledGuiState(matedState)
    settled shouldBe GuiState.GameFinished(GameStatus.Checkmate(Color.White))
  }

  // ── Castling: no animation, immediate settle ──────────────────────────────

  it should "settle immediately (no animation plan) for a castling move" in {
    val e1 = algPos("e1")
    val g1 = algPos("g1")
    val h1 = algPos("h1")
    val e8 = algPos("e8")
    val board = Board.empty
      .place(e1, Piece(Color.White, PieceType.King))
      .place(h1, Piece(Color.White, PieceType.Rook))
      .place(e8, Piece(Color.Black, PieceType.King))
    import chess.domain.state.CastlingRights
    val state = freshState.copy(board = board, castlingRights = CastlingRights.full)
    val (_, vm1, _) = GameController.transition(state, freshVm(state), InputAction.SquareClicked(e1))
    val (_, vm2, plan) = GameController.transition(state, vm1, InputAction.SquareClicked(g1))
    plan           shouldBe None                        // castling not animated
    vm2.guiState   shouldBe GuiState.WaitingForSelection  // settled immediately
  }

  // ── currentGameState ───────────────────────────────────────────────────────

  "GameController.currentGameState" should "initially return a new-game state" in {
    val controller = new GameController(_ => (), _ => ())
    controller.currentGameState.currentPlayer shouldBe Color.White
    controller.currentGameState.board         shouldBe Board.initial
  }

  it should "reflect the state after loadGameState" in {
    val imported   = freshState.copy(currentPlayer = Color.Black)
    val controller = new GameController(_ => (), _ => ())
    controller.loadGameState(imported)
    controller.currentGameState.currentPlayer shouldBe Color.Black
  }

  // ── loadGameState ──────────────────────────────────────────────────────────

  "GameController.loadGameState" should "replace internal state with the imported GameState" in {
    val imported   = freshState.copy(currentPlayer = Color.Black)
    val controller = new GameController(_ => (), _ => ())
    controller.loadGameState(imported)
    controller.currentGameState shouldBe imported
  }

  it should "rebuild currentViewModel from the imported state" in {
    val imported   = freshState.copy(currentPlayer = Color.Black)
    val controller = new GameController(_ => (), _ => ())
    controller.loadGameState(imported)
    // ViewModel must reflect Black to move, not the initial White-to-move state
    controller.currentViewModel.statusText should include("Black")
  }

  it should "call onRefresh with the rebuilt view model" in {
    var refreshed: Option[GameViewModel] = None
    val controller = new GameController(vm => refreshed = Some(vm), _ => ())
    val imported   = freshState.copy(currentPlayer = Color.Black)
    controller.loadGameState(imported)
    refreshed            should not be empty
    refreshed.get.statusText should include("Black")
  }

  it should "not call onAnimate during loadGameState" in {
    var animateCalled = false
    val controller = new GameController(_ => (), _ => { animateCalled = true })
    controller.loadGameState(freshState)
    animateCalled shouldBe false
  }

  it should "settle to WaitingForSelection for an Ongoing imported state" in {
    val controller = new GameController(_ => (), _ => ())
    controller.loadGameState(freshState)
    controller.currentViewModel.guiState shouldBe GuiState.WaitingForSelection
  }

  it should "settle to GameFinished when the imported state is a Checkmate" in {
    val wK = Piece(Color.White, PieceType.King)
    val wQ = Piece(Color.White, PieceType.Queen)
    val wR = Piece(Color.White, PieceType.Rook)
    val bK = Piece(Color.Black, PieceType.King)
    val board = Board.empty
      .place(algPos("a8"), bK)
      .place(algPos("a1"), wR)
      .place(algPos("b6"), wQ)
      .place(algPos("h1"), wK)
    val checkmateState = freshState.copy(
      board         = board,
      currentPlayer = Color.Black,
      status        = GameStatus.Checkmate(Color.White)
    )
    val controller = new GameController(_ => (), _ => ())
    controller.loadGameState(checkmateState)
    controller.currentViewModel.guiState shouldBe GuiState.GameFinished(GameStatus.Checkmate(Color.White))
  }

  it should "clear stale PieceSelected GUI state when loading new state mid-selection" in {
    // Start a game, select a piece to enter PieceSelected state, then load new state
    val controller = new GameController(_ => (), _ => ())
    controller.handle(InputAction.SquareClicked(algPos("e2")))
    controller.currentViewModel.guiState shouldBe a[GuiState.PieceSelected]
    // Now load a fresh imported state — selection must be gone
    controller.loadGameState(freshState)
    controller.currentViewModel.guiState shouldBe GuiState.WaitingForSelection
  }
