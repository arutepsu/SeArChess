package chess.adapter.gui.assets

/** Pure algorithm that maps a `[0, 1]` animation progress value to a
 *  [[PlaybackResolution]] for a multi-segment sprite-sheet sequence.
 *
 *  === Segment distribution ===
 *  Progress is divided evenly across all segments.  For a sequence of N
 *  segments each segment owns a `1/N` slice of the `[0, 1)` range.  The
 *  cumulative boundary positions are computed with `scanLeft`, which requires
 *  no branching and handles any number of segments uniformly.
 *
 *  === PlaybackMode ===
 *  - `Clamp`: progress is clamped to `[0, 1]` before distribution.
 *    At `p ≥ 1` the last segment's last frame is held.
 *  - `Loop`: progress is reduced to its fractional part
 *    (`p - math.floor(p)`) before distribution, so the sequence repeats
 *    indefinitely.  This is branch-free: `math.floor(p)` is 0 for all
 *    `p ∈ [0, 1)` so it is a no-op within a single animation cycle, and
 *    coverage is exercised by a single out-of-range test case.
 *
 *  === Frame index within a segment ===
 *  Given the segment-local progress `q ∈ [0, 1]`, the frame index is
 *  `floor(q × frameCount)` clamped to `[0, frameCount − 1]`.
 *  Falls back to frame 0 when [[SpriteMetadataRepository]] has no entry for
 *  the segment.
 */
object SequencePlaybackPolicy:

  /** Resolve the segment and frame index for the given overall progress.
   *
   *  @param metadata  the playback descriptor for the logical state
   *  @param progress  overall animation progress; may be outside `[0, 1]`
   *  @param metaRepo  metadata repository used to look up the frame count for
   *                   the active segment; falls back to 1 when the key is absent
   *  @return          the resolved segment asset key and zero-based frame index
   */
  def resolve(
      metadata: StatePlaybackMetadata,
      progress: Double,
      metaRepo: SpriteMetadataRepository
  ): PlaybackResolution =
    val n = metadata.segments.length

    // Normalise progress according to playback mode (branch-free for Loop).
    val p = metadata.mode match
      case PlaybackMode.Clamp => progress.max(0.0).min(1.0)
      case PlaybackMode.Loop  => progress - math.floor(progress)

    // Cumulative boundary positions: 0, 1/n, 2/n, …, 1.
    // scanLeft produces n+1 values; zip with the segment list gives n pairs
    // of (segment, startFraction).  The active segment is the last one whose
    // start ≤ p (guaranteed by takeWhile + last).
    val sliceSize  = 1.0 / n
    val boundaries = (0 to n).scanLeft(0.0)((acc, _) => acc + sliceSize).take(n + 1)
    val pairs      = metadata.segments.zip(boundaries)

    // Select the last segment whose boundary start is ≤ p.
    // At p = 0 the first segment is selected; at p = 1 the last segment is
    // selected because we clamped p to ≤ 1 above.
    val (segment, segStart) = pairs.takeWhile((_, b) => b <= p).lastOption
      .getOrElse(pairs.head)

    // Segment-local progress: rescale p from the segment's [start, start+slice)
    // window into [0, 1].
    val localProgress = ((p - segStart) / sliceSize).max(0.0).min(1.0)

    // Frame index within the segment.
    val frameCount = metaRepo.lookup(segment.assetKey)
      .map(_.frameCount).getOrElse(1)
    val frameIndex = (localProgress * frameCount).toInt.min(frameCount - 1).max(0)

    PlaybackResolution(segment.assetKey, frameIndex)
