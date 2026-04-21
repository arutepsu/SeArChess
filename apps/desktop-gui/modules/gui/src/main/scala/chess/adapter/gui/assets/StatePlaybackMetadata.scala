package chess.adapter.gui.assets

/** Full playback descriptor for one logical [[VisualState]].
  *
  * Bundles the ordered sequence of sprite-sheet segments that together form the animation for a
  * single state, plus the [[PlaybackMode]] that governs what happens when the sequence ends.
  *
  * Most states have a single segment. Multi-segment states (e.g. `Attack`) list their segments in
  * display order: progress [0, 1) is divided evenly across all segments by
  * [[SequencePlaybackPolicy]].
  *
  * @param state
  *   the logical visual state this descriptor belongs to
  * @param segments
  *   ordered list of sprite-sheet segment references; must be non-empty
  * @param mode
  *   playback mode applied to the complete sequence
  */
final case class StatePlaybackMetadata(
    state: VisualState,
    segments: Seq[PlaybackSegmentRef],
    mode: PlaybackMode
)
