package chess.adapter.gui.animation

/** Snapshot of an in-progress animation frame, produced by [[AnimationRunner]]
 *  on every timer tick.
 *
 *  @param plan     the animation being executed
 *  @param progress fraction elapsed: 0.0 = start, 1.0 = complete
 */
final case class AnimationState(
  plan:     AnimationPlan,
  progress: Double
):
  /** True while the animation has not yet completed. */
  def isActive: Boolean = progress < 1.0

  /** Progress clamped to [0.0, 1.0]. */
  def clampedProgress: Double = progress.max(0.0).min(1.0)

object AnimationState:
  /** Below this progress threshold the captured piece is still visible.
   *  Above it the captured piece has "vanished" on impact. */
  val CaptureThreshold: Double = 0.65
