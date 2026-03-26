// $COVERAGE-OFF$
package chess.adapter.gui.animation

import javafx.animation.{AnimationTimer => JFXAnimationTimer}

/** Drives frame-by-frame animation using a JavaFX [[AnimationTimer]].
 *
 *  On each timer tick, fires [[onFrame]] with the current [[AnimationState]].
 *  When [[AnimationPlan.durationMs]] has elapsed, fires [[onComplete]] exactly
 *  once and stops the timer.
 *
 *  Mutable state is intentionally localised here; all callers interact through
 *  the two callbacks and [[start]] / [[stop]].
 *
 *  @param onFrame    called every animation frame with the current progress snapshot
 *  @param onComplete called once when the animation finishes
 */
class AnimationRunner(
    onFrame:    AnimationState => Unit,
    onComplete: () => Unit
):
  private var activePlan: Option[AnimationPlan] = None
  private var startNs:    Long                  = 0L

  private val timer = new JFXAnimationTimer:
    override def handle(now: Long): Unit =
      activePlan.foreach { plan =>
        val elapsedMs = (now - startNs).toDouble / 1_000_000.0
        val progress  = (elapsedMs / plan.durationMs).min(1.0)
        onFrame(AnimationState(plan, progress))
        if progress >= 1.0 then
          AnimationRunner.this.stop()
          onComplete()
      }

  /** Start animating [[plan]], replacing any in-progress animation. */
  def start(plan: AnimationPlan): Unit =
    stop()
    activePlan = Some(plan)
    startNs    = System.nanoTime()
    timer.start()

  /** Abort any in-progress animation without firing [[onComplete]]. */
  def stop(): Unit =
    timer.stop()
    activePlan = None
