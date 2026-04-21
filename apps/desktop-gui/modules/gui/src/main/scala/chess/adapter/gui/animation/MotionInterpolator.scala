package chess.adapter.gui.animation

import scala.math.{pow, sqrt}

/** Pure path interpolation for each [[MotionStyle]].
  *
  * Given source and destination pixel coordinates and a normalised progress value `t ∈ [0, 1]`,
  * returns the board-local pixel position `(x, y)` of the animated piece for that frame.
  *
  * No ScalaFX dependencies; no mutable state.
  */
object MotionInterpolator:

  /** Progress fraction at which an attack lunge reaches its overshoot point.
    *
    * Kept local to motion interpolation so motion shape remains independent from higher-level
    * capture phase choreography.
    */
  private val AttackLungePeakT: Double = 0.65

  /** Compute the piece position at normalised time [[t]] for the given [[style]].
    *
    * @param style
    *   motion character for this piece type
    * @param fromX
    *   source square top-left x (pixels, board-local)
    * @param fromY
    *   source square top-left y (pixels, board-local)
    * @param toX
    *   destination square top-left x
    * @param toY
    *   destination square top-left y
    * @param t
    *   normalised progress in [0, 1]; callers are responsible for clamping
    * @return
    *   `(x, y)` board-local pixel position
    */
  def interpolate(
      style: MotionStyle,
      fromX: Double,
      fromY: Double,
      toX: Double,
      toY: Double,
      t: Double
  ): (Double, Double) =
    style match
      case MotionStyle.Linear =>
        (lerp(fromX, toX, t), lerp(fromY, toY, t))

      case MotionStyle.Smooth =>
        val e = smoothstep(t)
        (lerp(fromX, toX, e), lerp(fromY, toY, e))

      case MotionStyle.Heavy =>
        val e = easeInOut(t)
        (lerp(fromX, toX, e), lerp(fromY, toY, e))

      case MotionStyle.Arc(heightFraction) =>
        val e = smoothstep(t)
        val baseX = lerp(fromX, toX, e)
        val baseY = lerp(fromY, toY, e)
        val dist = sqrt(pow(toX - fromX, 2) + pow(toY - fromY, 2))
        val lift = 4.0 * heightFraction * dist * t * (1.0 - t)
        (baseX, baseY - lift)

      case MotionStyle.AttackLunge(overshootFraction) =>
        val dx = toX - fromX
        val dy = toY - fromY
        val dist = sqrt(dx * dx + dy * dy)
        if dist == 0.0 then (toX, toY)
        else
          val overshootDist = overshootFraction * dist
          val osX = toX + (dx / dist) * overshootDist
          val osY = toY + (dy / dist) * overshootDist
          val peakT = AttackLungePeakT

          if t <= peakT then
            val s = smoothstep(t / peakT)
            (lerp(fromX, osX, s), lerp(fromY, osY, s))
          else
            val s = smoothstep((t - peakT) / (1.0 - peakT))
            (lerp(osX, toX, s), lerp(osY, toY, s))

  private def lerp(from: Double, to: Double, t: Double): Double =
    from + (to - from) * t

  private def smoothstep(t: Double): Double =
    t * t * (3.0 - 2.0 * t)

  private def easeInOut(t: Double): Double =
    if t < 0.5 then 4.0 * t * t * t
    else 1.0 - pow(-2.0 * t + 2.0, 3) / 2.0
