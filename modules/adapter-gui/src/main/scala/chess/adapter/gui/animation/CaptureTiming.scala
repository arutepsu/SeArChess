package chess.adapter.gui.animation

/** Phase resolver for capture animations.
 *
 *  Converts global normalized progress in [0, 1] into:
 *  - the active capture phase
 *  - local phase progress in [0, 1]
 *
 *  Capture sequence:
 *    Approach -> Attack -> Attack1 -> Dead -> Fade
 */
object CaptureTiming:

  enum Phase:
    case Approach, Attack, Attack1, Dead, Fade

  final case class PhaseProgress(
    phase:         Phase,
    localProgress: Double
  )

  /** Resolve the active capture phase for the given animation plan and
   *  normalized global progress.
   *
   *  @param plan           animation plan; expected to represent a capture
   *  @param globalProgress normalized animation progress in [0, 1]
   */
  def resolve(plan: AnimationPlan, globalProgress: Double): PhaseProgress =
    val t       = globalProgress.max(0.0).min(1.0)
    val timings = plan.captureTimings
    val total   = timings.totalMs.toDouble
    val elapsed = t * total

    val endApproach = timings.approachMs.toDouble
    val endAttack   = endApproach + timings.attackMs.toDouble
    val endAttack1  = endAttack + timings.attack1Ms.toDouble
    val endDead     = endAttack1 + timings.deadMs.toDouble
    val endFade     = endDead + timings.fadeMs.toDouble

    def local(start: Double, end: Double): Double =
      if end <= start then 1.0
      else ((elapsed - start) / (end - start)).max(0.0).min(1.0)

    if elapsed < endApproach then
      PhaseProgress(Phase.Approach, local(0.0, endApproach))
    else if elapsed < endAttack then
      PhaseProgress(Phase.Attack, local(endApproach, endAttack))
    else if elapsed < endAttack1 then
      PhaseProgress(Phase.Attack1, local(endAttack, endAttack1))
    else if elapsed < endDead then
      PhaseProgress(Phase.Dead, local(endAttack1, endDead))
    else
      PhaseProgress(Phase.Fade, local(endDead, endFade))