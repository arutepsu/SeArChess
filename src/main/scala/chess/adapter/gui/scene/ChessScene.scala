// $COVERAGE-OFF$
package chess.adapter.gui.scene

import chess.adapter.gui.animation.{AnimationPlan, AnimationPresentationMapper, AnimationRenderModel, AnimationRunner, AnimationState}
import chess.adapter.gui.assets.{PieceNodeFactory, PieceVisualId, SpriteSheetLoader, VisualState}
import chess.adapter.gui.controller.GameController
import chess.adapter.gui.input.InputAction
import chess.adapter.gui.render.{BoardRenderer, PromotionOverlay, StatusRenderer}
import chess.adapter.gui.viewmodel.GameViewModel
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.Scene
import scalafx.scene.layout.{BorderPane, Pane, StackPane}


/** Assembles the board, status bar, promotion overlay, and animation layer
 *  into a single [[Scene]].
 *
 *  Wiring:
 *  - Every [[InputAction]] routes through [[GameController.handle]].
 *  - The controller calls [[refresh]] on every state change.
 *  - When an animatable move occurs the controller calls [[startAnimation]].
 *  - [[AnimationRunner]] fires [[onAnimationFrame]] each FX tick; the frame
 *    data is mapped to [[AnimationRenderModel]] by [[AnimationPresentationMapper]]
 *    so this class contains no animation-presentation logic.
 *  - On completion the runner calls [[onAnimationComplete]], which delegates
 *    to [[GameController.completeAnimation]].
 *
 *  This class contains no chess logic and no animation-presentation policy.
 */
class ChessScene:

  // ── Asset pipeline ────────────────────────────────────────────────────────

  /** Shared loader + factory — the single asset path used by all rendering sites. */
  private val spriteLoader = new SpriteSheetLoader
  private val factory      = new PieceNodeFactory(spriteLoader)

  // ── Animation layer ───────────────────────────────────────────────────────

  /** Most-recently computed render model.  Used by [[refresh]] to supply the
   *  correct suppressed square to the board renderer between animation frames. */
  private var currentRenderModel: Option[AnimationRenderModel] = None

  private val runner = new AnimationRunner(onAnimationFrame, onAnimationComplete)

  // ── Controller ────────────────────────────────────────────────────────────

  private val controller = new GameController(refresh, startAnimation)
  private var vm: GameViewModel = controller.currentViewModel

  // ── Widgets ───────────────────────────────────────────────────────────────

  private val boardGrid   = BoardRenderer.create(vm, handle, factory)
  private val statusLabel = StatusRenderer.create(vm)

  /** Transparent Pane covering the board.  Holds animated piece nodes. */
  private val animLayer = new Pane:
    mouseTransparent = true
    prefWidth        = BoardRenderer.SquareSize * 8
    prefHeight       = BoardRenderer.SquareSize * 8

  private val overlayContainer = new StackPane:
    alignment        = Pos.Center
    mouseTransparent = false

  private val boardStack = new StackPane:
    alignment = Pos.Center
    children  = Seq(boardGrid, overlayContainer, animLayer)

  private val root = new BorderPane:
    center = boardStack
    bottom = statusLabel
    style  = "-fx-background-color: #312e2b;"
    BorderPane.setMargin(boardStack, Insets(16))
    BorderPane.setAlignment(statusLabel, Pos.Center)

  val scene: Scene = new Scene(root)

  refresh(vm)

  // ── Wiring ────────────────────────────────────────────────────────────────

  private def handle(action: InputAction): Unit = controller.handle(action)

  // ── Refresh ───────────────────────────────────────────────────────────────

  private def refresh(newVm: GameViewModel): Unit =
    vm = newVm
    // Use the suppressed square from the current render model if animation is active.
    BoardRenderer.update(boardGrid, newVm, handle, factory, currentRenderModel.flatMap(_.suppressedSquare))
    StatusRenderer.update(statusLabel, newVm)
    updateOverlay(newVm)

  private def updateOverlay(newVm: GameViewModel): Unit =
    overlayContainer.children.clear()
    newVm.promotion.foreach { promoVm =>
      overlayContainer.children.add(PromotionOverlay.create(promoVm, handle, factory))
    }

  // ── Animation ─────────────────────────────────────────────────────────────

  private def startAnimation(plan: AnimationPlan): Unit =
    currentRenderModel = None
    runner.start(plan)

  private def onAnimationFrame(state: AnimationState): Unit =
    val model = AnimationPresentationMapper.map(state)
    currentRenderModel = Some(model)
    BoardRenderer.update(boardGrid, vm, handle, factory, model.suppressedSquare)
    renderAnimationLayer(model)

  private def onAnimationComplete(): Unit =
    currentRenderModel = None
    animLayer.children.clear()
    controller.completeAnimation()

  /** Render the animation overlay from the pre-computed [[AnimationRenderModel]].
   *  All positions, opacities, and visibility decisions come from the model.
   *  All visual-asset decisions are delegated to [[PieceNodeFactory]] — no
   *  rendering logic lives here. */
  private def renderAnimationLayer(model: AnimationRenderModel): Unit =
    animLayer.children.clear()

    // Captured piece (fading out) — rendered first so it appears behind the mover.
    model.capturedPiece.foreach { info =>
      val (color, pieceType) = info.piece
      animLayer.children.add(
        factory.positioned(PieceVisualId(color, pieceType, VisualState.Dead),
          info.x, info.y, BoardRenderer.SquareSize, info.opacity, info.frameIndex))
    }

    // Moving piece — rendered on top of the captured piece.
    val mover = model.movingPiece
    val (mc, mpt) = mover.piece
    animLayer.children.add(
      factory.positioned(PieceVisualId(mc, mpt, VisualState.Move),
        mover.x, mover.y, BoardRenderer.SquareSize, mover.opacity, mover.frameIndex))
