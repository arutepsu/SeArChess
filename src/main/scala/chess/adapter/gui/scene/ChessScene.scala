// $COVERAGE-OFF$
package chess.adapter.gui.scene

import chess.adapter.gui.animation.{AnimationPlan, AnimationPresentationMapper, AnimationRenderModel, AnimationRunner, AnimationState}
import chess.adapter.gui.assets.{PieceNodeFactory, PieceVisualId, SpriteCatalogLoader, SpriteMetadataRepository, SpriteSheetLoader, StatePlaybackRepository}
import chess.adapter.gui.controller.GameController
import chess.adapter.gui.input.InputAction
import chess.adapter.gui.render.{BoardRenderer, PromotionOverlay, StaticPieceOverlayRenderer, StatusRenderer}
import chess.adapter.gui.viewmodel.GameViewModel
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.Scene
import scalafx.scene.layout.{BorderPane, Pane, StackPane}

/** Assembles the board, status bar, promotion overlay, and animation layer
 *  into a single [[Scene]].
 */
class ChessScene:

  private val catalog      = SpriteCatalogLoader.load()
  private val metaRepo     = SpriteMetadataRepository.fromCatalog(catalog)
  private val playbackRepo = StatePlaybackRepository.fromCatalog(catalog)

  private val spriteLoader = new SpriteSheetLoader
  private val factory      = new PieceNodeFactory(spriteLoader, metaRepo)

  private var currentRenderModel: Option[AnimationRenderModel] = None

  private val mapper = new AnimationPresentationMapper(metaRepo, playbackRepo)
  private val runner = new AnimationRunner(onAnimationFrame, onAnimationComplete)

  private val controller = new GameController(refresh, startAnimation)
  private var vm: GameViewModel = controller.currentViewModel

  private val boardGrid          = BoardRenderer.create(vm, handle, factory)
  private val staticPieceOverlay = StaticPieceOverlayRenderer.create(vm, factory)
  private val statusLabel        = StatusRenderer.create(vm)

  private val animLayer = new Pane:
    mouseTransparent = true
    prefWidth        = BoardRenderer.SquareSize * 8
    prefHeight       = BoardRenderer.SquareSize * 8

  private val overlayContainer = new StackPane:
    alignment        = Pos.Center
    mouseTransparent = true

  private val boardStack = new StackPane:
    alignment = Pos.Center
    children  = Seq(boardGrid, staticPieceOverlay, overlayContainer, animLayer)

  private val root = new BorderPane:
    center = boardStack
    bottom = statusLabel
    style  = "-fx-background-color: #312e2b;"
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

  private def updateOverlay(newVm: GameViewModel): Unit =
    overlayContainer.children.clear()
    newVm.promotion match
      case Some(promoVm) =>
        overlayContainer.mouseTransparent = false
        overlayContainer.children.add(PromotionOverlay.create(promoVm, handle, factory))
      case None =>
        overlayContainer.mouseTransparent = true

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
          scale = info.scale
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