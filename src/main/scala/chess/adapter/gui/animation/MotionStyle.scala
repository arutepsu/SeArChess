package chess.adapter.gui.animation

/** Declarative description of movement path and feel for a piece type.
 *
 *  Values carry only the parameters needed to characterise the motion;
 *  the actual interpolation math lives in [[MotionInterpolator]].
 */
enum MotionStyle:

  /** Pure constant-speed straight-line path — simple and direct. */
  case Linear

  /** Straight-line path with cubic smoothstep easing — fluid, light glide. */
  case Smooth

  /** Straight-line path with quartic ease-in-out — more deliberate, weighty feel. */
  case Heavy

  /** Parabolic arc — piece jumps upward (negative screen-y) along a curve.
   *
   *  @param heightFraction peak lift height as a fraction of the Euclidean move
   *                        distance (e.g. 0.5 → half the move distance lifted at peak)
   */
  case Arc(heightFraction: Double)
