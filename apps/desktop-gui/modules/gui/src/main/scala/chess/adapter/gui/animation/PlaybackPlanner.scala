package chess.adapter.gui.animation

import chess.adapter.gui.assets.{PlaybackMode, StatePlaybackMetadata}

/** Pure segment-selection step for multi-segment sprite playback.
  *
  * Maps a global normalized progress in `[0, 1]` to the active segment asset key and a local
  * normalized progress within that segment, ready for
  * [[chess.adapter.gui.assets.FrameSelectionPolicy.select]].
  *
  * ===Segment distribution===
  * Progress is divided evenly across all segments. The active segment index is `floor(progress ×
  * segmentCount)` clamped to `[0, segmentCount − 1]`; no branching is needed beyond the mode
  * normalisation.
  *
  * ===PlaybackMode===
  * Applied before distribution:
  *   - `Clamp`: global progress is clamped to `[0, 1]`.
  *   - `Loop`: global progress is reduced to its fractional part (`p − floor(p)`), repeating the
  *     sequence indefinitely.
  */
object PlaybackPlanner:

  /** Result of segment selection for one animation frame.
    *
    * @param segmentAssetKey
    *   asset key of the sprite-sheet segment that is active at the given progress (e.g.
    *   `"classic/white_pawn_attack1"`)
    * @param localProgress
    *   normalised progress within this segment, in `[0, 1]`; ready for
    *   [[chess.adapter.gui.assets.FrameSelectionPolicy.select]]
    */
  final case class Plan(segmentAssetKey: String, localProgress: Double)

  /** Select the active segment and compute local progress for [[progress]].
    *
    * @param metadata
    *   playback descriptor for the logical state
    * @param progress
    *   overall animation progress; may be outside `[0, 1]`
    * @return
    *   the active segment asset key and local progress in `[0, 1]`
    */
  def plan(metadata: StatePlaybackMetadata, progress: Double): Plan =
    val n = metadata.segments.length
    val p = metadata.mode match
      case PlaybackMode.Clamp => progress.max(0.0).min(1.0)
      case PlaybackMode.Loop  => progress - math.floor(progress)
    val sliceSize = 1.0 / n
    val idx = (p / sliceSize).toInt.min(n - 1).max(0)
    val segStart = idx * sliceSize
    Plan(
      segmentAssetKey = metadata.segments(idx).assetKey,
      localProgress = ((p - segStart) / sliceSize).max(0.0).min(1.0)
    )
