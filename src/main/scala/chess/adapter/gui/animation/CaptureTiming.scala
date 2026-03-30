package chess.adapter.gui.animation

/** Shared presentation-level timing constants for capture animations.
 *
 *  All four values divide the normalised progress range [0, 1] into the
 *  visual phases that govern both the captured-piece presentation (in
 *  [[AnimationPresentationMapper]]) and the attacker's lunge trajectory
 *  (in [[MotionInterpolator]]).
 *
 *  {{{
 *  0 ──── HitStart ──── DeadStart ──── FadeEnd ──── 1
 *         │               │              │
 *         Idle→Hit         Hit→Dead       Dead→hidden
 *
 *  0 ──── LungePeakT ────────────────────────────── 1
 *         │
 *         attacker reaches overshoot; phase 2 recoil begins
 *  }}}
 *
 *  Ordering invariant: `HitStart < DeadStart < FadeEnd < 1.0`
 *  and `LungePeakT < FadeEnd`.
 */
object CaptureTiming:

  /** Captured piece enters `Hit` state at this progress.
   *
   *  Set to align with the moment the attacker first crosses the destination
   *  square during the lunge.  With overshoot fraction 0.15 and LungePeakT 0.65
   *  the attacker reaches the destination at approximately t ≈ 0.52; 0.55 gives
   *  a short anticipation gap that feels natural without being perceptible.
   */
  val HitStart: Double = 0.55

  /** Captured piece enters `Dead` state (with fade) at this progress.
   *
   *  Hit window [HitStart, DeadStart] = 17% — long enough to read the reaction
   *  without lingering.
   */
  val DeadStart: Double = 0.72

  /** Captured piece fully faded out and hidden at this progress.
   *
   *  Dead/fade window [DeadStart, FadeEnd] = 18% — fade completes cleanly before
   *  the attacker finishes settling, so it never competes with the settle recoil.
   */
  val FadeEnd: Double = 0.90

  /** Progress fraction at which the attacker reaches the overshoot (lunge peak)
   *  during an [[MotionStyle.AttackLunge]].  Before this point the piece
   *  accelerates toward and past the destination; after it the piece settles
   *  back to the destination square.
   *
   *  Kept at 0.65: the overshoot peak is ~13% after attacker contact (~0.52),
   *  which gives the lunge a clear drive-through feel before the recoil.
   */
  val LungePeakT: Double = 0.65

  // ── Impact pause ────────────────────────────────────────────────────────────
  // A piecewise-linear remap of the normalised progress used only for capture
  // attacker motion.  Within a narrow window around the contact moment the
  // attacker slows to `ImpactPauseFactor` of its normal speed, then resumes
  // at a slightly elevated pace for the rest of the animation so the total
  // duration is unchanged (t=1 still maps to 1).
  //
  // Layout:
  //   0 ──── lo ──────── center ──────── hi ──────────── 1
  //               [impact pause zone — ImpactPauseFactor speed]

  /** Centre of the impact pause zone.  ~t=0.52 is when the attacker first
   *  crosses the destination square during a lunge (derived from the phase-1
   *  smoothstep with overshoot fraction 0.15 and LungePeakT 0.65).
   */
  val ImpactPauseCenter: Double = 0.52

  /** Half-width of the impact pause zone.  Zone spans
   *  `[ImpactPauseCenter − half, ImpactPauseCenter + half]` = `[0.46, 0.58]`.
   */
  val ImpactPauseHalf: Double = 0.06

  /** Speed factor inside the pause zone relative to normal (1.0) speed.
   *  0.40 means the zone is traversed at 40% of its proportional pace — a
   *  60% slowdown that reads as a micro hold without looking like a stutter.
   *  Outside the zone the speed is only ~8% higher to compensate, imperceptible.
   */
  val ImpactPauseFactor: Double = 0.40

  /** Remap normalised progress for capture attacker motion to produce a micro
   *  impact pause near the contact moment.
   *
   *  The mapping is piecewise linear:
   *  - `[0, lo]`   → linear with `outsideScale` (slightly faster)
   *  - `[lo, hi]`  → linear with `ImpactPauseFactor × outsideScale` (slowed)
   *  - `[hi, 1]`   → linear with `outsideScale` (slightly faster)
   *
   *  Invariants: `remapCapture(0) = 0`, `remapCapture(1) = 1`, monotone.
   *
   *  @param t normalised progress in [0, 1]
   */
  def remapCapture(t: Double): Double =
    val lo           = ImpactPauseCenter - ImpactPauseHalf
    val hi           = ImpactPauseCenter + ImpactPauseHalf
    val zoneWidth    = 2.0 * ImpactPauseHalf
    val zoneOutput   = zoneWidth * ImpactPauseFactor
    val outsideScale = (1.0 - zoneOutput) / (1.0 - zoneWidth)
    if t <= lo then t * outsideScale
    else if t <= hi then lo * outsideScale + ((t - lo) / zoneWidth) * zoneOutput
    else lo * outsideScale + zoneOutput + (t - hi) * outsideScale
