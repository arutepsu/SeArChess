package chess.adapter.gui.assets

/** Pure, centralized frame-index selection policy for sprite-sheet playback.
  *
  * Given a [[VisualState]], a total frame count from [[SpriteMetadata]], and a normalised animation
  * [[progress]] in `[0, 1]`, this object returns the zero-based frame index that should be
  * displayed.
  *
  * ===Playback rules===
  *
  * {{{
  *    Idle          → frame 0 always (static board pieces, promotion choices)
  *    Move          → progress-based across all frames (animated moving piece)
  *    Dead          → progress-based across all frames (fading captured piece)
  *    Attack / Hit  → progress-based (reserved for future use)
  * }}}
  *
  * ===Progress-based formula===
  *
  * {{{
  *    index = clamp(floor(progress × frameCount), 0, frameCount − 1)
  * }}}
  *
  * Out-of-range `progress` values are clamped to `[0, 1]` before computing the index, so callers do
  * not need to guard against overflow.
  *
  * This object has no ScalaFX dependency and no mutable state. It is intentionally untuned: timing
  * curves, looping, and per-asset overrides can be layered on here once real sprite assets are
  * available.
  */
object FrameSelectionPolicy:

  /** Select the frame index to display.
    *
    * @param state
    *   visual state of the piece
    * @param frameCount
    *   total frames in the sprite sheet (from [[SpriteMetadata]])
    * @param progress
    *   normalised playback position in `[0, 1]`; out-of-range values are clamped before use
    * @return
    *   zero-based frame index in `[0, frameCount − 1]`
    */
  def select(state: VisualState, frameCount: Int, progress: Double): Int =
    state match
      case VisualState.Idle => 0
      case VisualState.Move | VisualState.Dead | VisualState.Attack | VisualState.Hit =>
        progressBased(frameCount, progress)

  // ── Private ─────────────────────────────────────────────────────────────────

  private def progressBased(frameCount: Int, progress: Double): Int =
    val clamped = progress.max(0.0).min(1.0)
    (clamped * frameCount).toInt.min(frameCount - 1).max(0)
