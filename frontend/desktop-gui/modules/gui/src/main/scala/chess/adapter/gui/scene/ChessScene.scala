// $COVERAGE-OFF$
package chess.adapter.gui.scene

import chess.adapter.gui.animation.{
  AnimationPlan,
  AnimationPresentationMapper,
  AnimationRenderModel,
  AnimationRunner,
  AnimationState
}
import chess.adapter.gui.assets.{
  PieceNodeFactory,
  PieceVisualId,
  SpriteCatalogLoader,
  SpriteMetadataRepository,
  SpriteSheetLoader,
  StatePlaybackRepository
}
import chess.adapter.gui.controller.GameController
import chess.adapter.gui.input.InputAction
import chess.adapter.gui.notation.{GuiNotationApi, NotationSidebar, NotationSidebarController}
import chess.adapter.gui.render.{
  BoardRenderer,
  MoveHistoryPanel,
  PromotionOverlay,
  StaticPieceOverlayRenderer,
  StatusRenderer
}
import chess.adapter.gui.viewmodel.{GameViewModel, MoveHistoryViewModelMapper}
import chess.application.session.model.DesktopSessionContext
import chess.application.session.service.{GameSessionCommands, SessionLifecycleService}
import chess.domain.state.GameState
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.Scene
import scalafx.scene.layout.{BorderPane, Pane, StackPane}

/** Assembles the board, status bar, promotion overlay, animation layer, and right-side tools panel
  * (notation + move history) into a single [[Scene]].
  *
  * The session dependencies — [[GameSessionCommands]], [[sessionLifecycleService]], and the current
  * [[DesktopSessionContext]] — are provided by the composition root (e.g. `game-service/Main`).
  * GUI, TUI, and any other adapters share the same command boundary and session identity so that
  * moves from any adapter are authoritative over the same repository-backed game state.
  *
  * [[GameStateObservable]] acts only as a cross-adapter notification bridge. It is updated after
  * successful mutations so other adapters (e.g. TUI) observe the new state, but it is not the
  * source of truth.
  *
  * @param game
  *   cross-adapter notification bridge; updated after every successful move so other adapters (e.g.
  *   TUI) observe it
  * @param commands
  *   single write boundary for session-aware game mutations
  * @param sessionLifecycleService
  *   session lifecycle operations (promotion, import provisioning)
  * @param sessionContext
  *   the shared [[DesktopSessionContext]] (created once at startup)
  */
class ChessScene(
    game: chess.application.GameStateObservable,
    commands: GameSessionCommands,
    sessionLifecycleService: SessionLifecycleService,
    sessionContext: DesktopSessionContext
):

  private val catalog = SpriteCatalogLoader.load()
  private val metaRepo = SpriteMetadataRepository.fromCatalog(catalog)
  private val playbackRepo = StatePlaybackRepository.fromCatalog(catalog)

  private val spriteLoader = new SpriteSheetLoader
  private val factory = new PieceNodeFactory(spriteLoader, metaRepo)

  private var currentRenderModel: Option[AnimationRenderModel] = None

  private val mapper = new AnimationPresentationMapper(metaRepo, playbackRepo)
  private val runner = new AnimationRunner(onAnimationFrame, onAnimationComplete)

  // Session and repositories are provided by the composition root.
  // ChessScene is no longer responsible for creating session infrastructure.

  private val controller =
    new GameController(game, refresh, startAnimation, commands, sessionLifecycleService, sessionContext)
  private var vm: GameViewModel = controller.currentViewModel

  private val boardGrid = BoardRenderer.create(vm, handle, factory)
  private val staticPieceOverlay = StaticPieceOverlayRenderer.create(vm, factory)
  private val statusLabel = StatusRenderer.create(vm)

  private val animLayer = new Pane:
    mouseTransparent = true
    prefWidth = BoardRenderer.SquareSize * 8
    prefHeight = BoardRenderer.SquareSize * 8

  private val overlayContainer = new StackPane:
    alignment = Pos.Center
    mouseTransparent = true

  private val boardStack = new StackPane:
    alignment = Pos.Center
    children = Seq(boardGrid, staticPieceOverlay, overlayContainer, animLayer)

  // ── Notation sidebar ────────────────────────────────────────────────────────

  private val notationApi = GuiNotationApi.default

  // Break the sidebarController ↔ sidebar cycle: use a var to forward the
  // refresh callback after both objects exist.
  private var sidebarRefresh: chess.adapter.gui.notation.NotationSidebarState => Unit = _ => ()

  private val sidebarController = new NotationSidebarController(
    api = notationApi,
    stateProvider = () => controller.currentGameState,
    onImportedState = applyImportedState,
    onRefresh = state => sidebarRefresh(state)
  )

  private val sidebar = new NotationSidebar(sidebarController)

  // Wire the real refresh now that sidebar exists
  sidebarRefresh = sidebar.refresh

  // ── History panel + composed side panel ─────────────────────────────────────

  private val historyPanel = new MoveHistoryPanel
  private val sidePanel = new GameSidePanel(sidebar, historyPanel)

  // ── Root layout ─────────────────────────────────────────────────────────────

  private val root = new BorderPane:
    center = boardStack
    bottom = statusLabel
    right = sidePanel.root
    style = "-fx-background-color: #312e2b;"
    BorderPane.setMargin(boardStack, Insets(16))
    BorderPane.setAlignment(statusLabel, Pos.Center)

  val scene: Scene = new Scene(root)

  refresh(vm)

  private def handle(action: InputAction): Unit = controller.handle(action)

  private def refresh(newVm: GameViewModel): Unit =
    vm = newVm
    val suppressed = currentRenderModel.flatMap(_.suppressedSquare)
    BoardRenderer.update(boardGrid, newVm, handle, factory, suppressed)
    StaticPieceOverlayRenderer.update(staticPieceOverlay, newVm, factory, suppressed)
    StatusRenderer.update(statusLabel, newVm)
    updateOverlay(newVm)
    sidePanel.refreshHistory(MoveHistoryViewModelMapper.map(controller.currentGameState))

  private def updateOverlay(newVm: GameViewModel): Unit =
    overlayContainer.children.clear()
    newVm.promotion match
      case Some(promoVm) =>
        overlayContainer.mouseTransparent = false
        overlayContainer.children.add(PromotionOverlay.create(promoVm, handle, factory))
      case None =>
        overlayContainer.mouseTransparent = true

  // ── Imported-state application ──────────────────────────────────────────────

  /** Apply an imported [[GameState]] received from the notation sidebar.
    *
    * Clears transient animation and overlay visuals that do not belong to the imported state, then
    * delegates to [[GameController.loadGameState]]. The existing [[refresh]] callback drives the
    * board/status/overlay update and also refreshes the history panel.
    */
  private def applyImportedState(importedState: GameState): Unit =
    // Clear transient animation state first so stale pieces do not remain
    runner.stop()
    currentRenderModel = None
    animLayer.children.clear()
    // Delegate; this triggers onRefresh → refresh(newVm) above
    controller.loadGameState(importedState)

  private def startAnimation(plan: AnimationPlan): Unit =
    currentRenderModel = None
    runner.start(plan)

  private def onAnimationFrame(state: AnimationState): Unit =
    val model = mapper.map(state)
    currentRenderModel = Some(model)
    BoardRenderer.update(boardGrid, vm, handle, factory, model.suppressedSquare)
    StaticPieceOverlayRenderer.update(staticPieceOverlay, vm, factory, model.suppressedSquare)
    renderAnimationLayer(model)

  private def onAnimationComplete(): Unit =
    currentRenderModel = None
    animLayer.children.clear()
    StaticPieceOverlayRenderer.update(staticPieceOverlay, vm, factory, None)
    controller.completeAnimation()

  private def renderAnimationLayer(model: AnimationRenderModel): Unit =
    animLayer.children.clear()

    model.capturedPiece.foreach { info =>
      val (color, pieceType) = info.piece
      animLayer.children.add(
        factory.positioned(
          PieceVisualId(color, pieceType, info.visualState),
          info.x,
          info.y,
          BoardRenderer.SquareSize,
          info.opacity,
          info.frameIndex,
          info.segmentAssetKey,
          info.flipX,
          info.scale
        )
      )
    }

    val mover = model.movingPiece
    val (mc, mpt) = mover.piece
    animLayer.children.add(
      factory.positioned(
        PieceVisualId(mc, mpt, mover.visualState),
        mover.x,
        mover.y,
        BoardRenderer.SquareSize,
        mover.opacity,
        mover.frameIndex,
        mover.segmentAssetKey,
        mover.flipX,
        mover.scale
      )
    )
