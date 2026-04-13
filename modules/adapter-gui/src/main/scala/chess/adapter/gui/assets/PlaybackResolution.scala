package chess.adapter.gui.assets

/** The fully-resolved playback output for a single animation frame.
 *
 *  Produced by [[SequencePlaybackPolicy.resolve]] and consumed by
 *  [[chess.adapter.gui.animation.AnimationPresentationMapper]] to fill
 *  [[chess.adapter.gui.animation.PieceRenderInfo]].
 *
 *  @param segmentAssetKey asset key of the sprite-sheet segment that should be
 *                         displayed on this frame (e.g. `"classic/white_knight_attack1"`)
 *  @param frameIndex      zero-based frame index within that segment's sprite sheet
 */
final case class PlaybackResolution(segmentAssetKey: String, frameIndex: Int)
