// $COVERAGE-OFF$
package chess.adapter.gui.render

import chess.adapter.gui.assets.{PieceNodeFactory, PieceVisualId, VisualState}
import chess.adapter.gui.viewmodel.GameViewModel
import chess.domain.model.Position
import javafx.animation.{AnimationTimer => JfxAnimationTimer}
import scalafx.scene.layout.Pane

import scala.collection.mutable

/** Renders all non-animated board pieces on a separate mouse-transparent overlay.
 *
 *  This decouples visual sprite size from board hit detection. Board clicks are
 *  handled entirely by the background grid; pieces are pure visuals here.
 *
 *  Static pieces continuously loop through their Idle animation frames.
 */
object StaticPieceOverlayRenderer:

  /** Idle playback speed in frames per second. */
  private val IdleFps: Double = 6.0

  private val paneState   = mutable.Map.empty[Pane, (GameViewModel, Option[Position])]
  private val paneTimers  = mutable.Map.empty[Pane, JfxAnimationTimer]

  def create(
      vm: GameViewModel,
      factory: PieceNodeFactory,
      suppressPos: Option[Position] = None
  ): Pane =
    val pane = new Pane:
      mouseTransparent = true
      pickOnBounds     = false
      prefWidth        = BoardRenderer.SquareSize * 8
      prefHeight       = BoardRenderer.SquareSize * 8

    paneState.update(pane, (vm, suppressPos))
    startIdleLoopIfNeeded(pane, factory)
    render(pane, vm, factory, suppressPos, frameTick = currentFrameTick())
    pane

  def update(
      pane: Pane,
      vm: GameViewModel,
      factory: PieceNodeFactory,
      suppressPos: Option[Position] = None
  ): Unit =
    paneState.update(pane, (vm, suppressPos))
    render(pane, vm, factory, suppressPos, frameTick = currentFrameTick())

  private def startIdleLoopIfNeeded(pane: Pane, factory: PieceNodeFactory): Unit =
    if paneTimers.contains(pane) then return

    val timer = new JfxAnimationTimer:
      override def handle(now: Long): Unit =
        paneState.get(pane).foreach { case (vm, suppressPos) =>
          render(pane, vm, factory, suppressPos, frameTick = frameTickFromNow(now))
        }

    paneTimers.update(pane, timer)
    timer.start()

  private def render(
      pane: Pane,
      vm: GameViewModel,
      factory: PieceNodeFactory,
      suppressPos: Option[Position],
      frameTick: Long
  ): Unit =
    pane.children.clear()

    for
      sv <- vm.squares
      if !suppressPos.contains(sv.position)
      (color, pieceType) <- sv.piece
    do
      val col = BoardProjection.toScreenCol(sv.position)
      val row = BoardProjection.toScreenRow(sv.position)

      val x = col * BoardRenderer.SquareSize
      val y = row * BoardRenderer.SquareSize

      val id         = PieceVisualId(color, pieceType, VisualState.Idle)
      val frameCount = factory.frameCountFor(id)
      val frameIndex =
        if frameCount <= 1 then 0
        else (frameTick % frameCount).toInt

      val pieceNode =
        factory.positioned(
          id         = id,
          x          = x,
          y          = y,
          squareSize = BoardRenderer.SquareSize,
          frameIndex = frameIndex,
          flipX      = PieceFacingPolicy.flipX(color)
        )

      pieceNode.mouseTransparent = true
      pane.children.add(pieceNode)

  private def frameTickFromNow(now: Long): Long =
    ((now / 1_000_000_000.0) * IdleFps).toLong

  private def currentFrameTick(): Long =
    ((System.nanoTime() / 1_000_000_000.0) * IdleFps).toLong