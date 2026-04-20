package chess.adapter.gui.assets

/** Reference to a single sprite-sheet segment within a multi-segment playback
 *  sequence.
 *
 *  A segment corresponds to one PNG asset on the classpath.  Multiple segments
 *  are chained together by [[StatePlaybackMetadata]] to form a composite
 *  animation for a single logical [[VisualState]].
 *
 *  @param assetKey the sprite-sheet asset key, as used by
 *                  [[SpriteMetadataRepository]] and [[SpriteSheetLoader]]
 *                  (e.g. `"classic/white_knight_attack"`)
 */
final case class PlaybackSegmentRef(assetKey: String)
