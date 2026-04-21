package chess.adapter.gui.animation

/** Declarative description of movement path and feel for a piece type.
  *
  * Values carry only the parameters needed to characterise the motion; the actual interpolation
  * math lives in [[MotionInterpolator]].
  */
enum MotionStyle:

  /** Pure constant-speed straight-line path — simple and direct. */
  case Linear

  /** Straight-line path with cubic smoothstep easing — fluid, light glide. */
  case Smooth

  /** Straight-line path with cubic ease-in-out — more deliberate, weighty feel. */
  case Heavy

  /** Parabolic arc — piece jumps upward (negative screen-y) along a curve.
    *
    * @param heightFraction
    *   peak lift height as a fraction of the Euclidean move distance (e.g. 0.5 → half the move
    *   distance lifted at peak)
    */
  case Arc(heightFraction: Double)

  /** Two-phase attack lunge — piece drives past the destination then settles back.
    *
    * Phase 1 `[0, LungePeakT]`: smoothstep from source to overshoot point. Phase 2 `(LungePeakT,
    * 1]`: smoothstep from overshoot back to destination.
    *
    * The overshoot point lies along the normalized source→destination vector, a distance of
    * `overshootFraction × move distance` past the destination. The final position at `t = 1` is
    * exactly the destination.
    *
    * @param overshootFraction
    *   fraction of the Euclidean move distance to overshoot (e.g. 0.15 → 15 % of move distance past
    *   destination)
    */
  case AttackLunge(overshootFraction: Double)
