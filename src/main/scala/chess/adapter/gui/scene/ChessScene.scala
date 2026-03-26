// $COVERAGE-OFF$
package chess.adapter.gui.scene

import chess.adapter.gui.animation.{AnimationPlan, AnimationRunner, AnimationState}
import chess.adapter.gui.assets.PieceSymbol
import chess.adapter.gui.controller.GameController
import chess.adapter.gui.input.InputAction
import chess.adapter.gui.render.{BoardRenderer, PromotionOverlay, StatusRenderer}
import chess.adapter.gui.viewmodel.GameViewModel
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.Scene
import scalafx.scene.layout.{BorderPane, Pane, StackPane, VBox}
import scalafx.scene.paint.Color as FxColor
import scalafx.scene.text.{Font, Text}

/** Assembles the board, status bar, promotion overlay, and animation layer
 *  into a single Scene.
 *
 *  The controller is wired here: every [[InputAction]] routes through
 *  [[GameController#handle]], which calls back into [[refresh]] with the new
 *  [[GameViewModel]].  When the controller detects an animatable move it also
 *  calls [[startAnimation]]; the [[AnimationRunner]] then fires frame callbacks
 *  that update [[animLayer]], and the completion callback returns to
 *  [[GameController#completeAnimation]].
 *
 *  This class contains no chess logic.
 */
class ChessScene:

  // ── Animation layer ──────────────────────────────────────────────────────

  private var animState: Option[AnimationState] = None

  private val runner = new AnimationRunner(onAnimationFrame, onAnimationComplete)

  // ── Controller ───────────────────────────────────────────────────────────

  private val controller = new GameController(refresh, startAnimation)
  private var vm: GameViewModel = controller.currentViewModel

  // ── Widgets ──────────────────────────────────────────────────────────────

  private val boardGrid   = BoardRenderer.create(vm, handle)
  private val statusLabel = StatusRenderer.create(vm)

  // Transparent Pane overlaid on the board — holds the animated piece node(s).
  private val animLayer = new Pane:
    mouseTransparent = true
    prefWidth        = BoardRenderer.SquareSize * 8
    prefHeight       = BoardRenderer.SquareSize * 8

  // Promotion chooser overlay
  private val overlayContainer = new StackPane:
    alignment       = Pos.Center
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

  // Initial render
  refresh(vm)

  // ── Wiring ───────────────────────────────────────────────────────────────

  private def handle(action: InputAction): Unit =
    controller.handle(action)

  // ── Refresh (called by controller on every state change) ─────────────────

  private def refresh(newVm: GameViewModel): Unit =
    vm = newVm
    BoardRenderer.update(boardGrid, newVm, handle, animState.map(_.plan.to))
    StatusRenderer.update(statusLabel, newVm)
    updateOverlay(newVm)

  private def updateOverlay(newVm: GameViewModel): Unit =
    overlayContainer.children.clear()
    newVm.promotion.foreach { promoVm =>
      overlayContainer.children.add(PromotionOverlay.create(promoVm, handle))
    }

  // ── Animation ────────────────────────────────────────────────────────────

  private def startAnimation(plan: AnimationPlan): Unit =
    animState = None
    runner.start(plan)

  private def onAnimationFrame(state: AnimationState): Unit =
    animState = Some(state)
    BoardRenderer.update(boardGrid, vm, handle, Some(state.plan.to))
    renderAnimationLayer(state)

  private def onAnimationComplete(): Unit =
    animState = None
    animLayer.children.clear()
    controller.completeAnimation()

  /** Render the animated piece (and optionally fading captured piece) in [[animLayer]]. */
  private def renderAnimationLayer(state: AnimationState): Unit =
    animLayer.children.clear()
    val S   = BoardRenderer.SquareSize
    val t   = state.clampedProgress
    val plan = state.plan

    // Lerp the moving piece from the source to the destination square (top-left corner).
    val fromX = plan.from.file * S
    val fromY = (7 - plan.from.rank) * S
    val toX   = plan.to.file * S
    val toY   = (7 - plan.to.rank) * S

    val currentX = fromX + (toX - fromX) * t
    val currentY = fromY + (toY - fromY) * t

    // Captured piece fades out in the first portion of the animation.
    if plan.isCapture && t < AnimationState.CaptureThreshold then
      val fade    = 1.0 - t / AnimationState.CaptureThreshold
      val capNode = makePieceNode(plan.capturedPiece.get, S)
      capNode.opacity  = fade
      capNode.layoutX  = toX
      capNode.layoutY  = toY
      animLayer.children.add(capNode)

    // Moving piece travels along the lerped path.
    val movNode = makePieceNode(plan.movingPiece, S)
    movNode.layoutX = currentX
    movNode.layoutY = currentY
    animLayer.children.add(movNode)

  /** Build a StackPane the size of one square, containing the piece glyph,
   *  positioned at the caller-supplied (layoutX, layoutY). */
  private def makePieceNode(piece: (chess.domain.model.Color, chess.domain.model.PieceType), squareSize: Double): StackPane =
    val (color, pieceType) = piece
    val glyph = new Text:
      text        = PieceSymbol.symbol(color, pieceType)
      font        = Font("Segoe UI Symbol", squareSize * 0.62)
      fill        = if color == chess.domain.model.Color.White then FxColor.web("#fffffe") else FxColor.web("#1a1a1a")
      stroke      = if color == chess.domain.model.Color.White then FxColor.web("#333333") else FxColor.web("#cccccc")
      strokeWidth = 0.6
    new StackPane:
      alignment  = Pos.Center
      prefWidth  = squareSize
      prefHeight = squareSize
      children   = Seq(glyph)
