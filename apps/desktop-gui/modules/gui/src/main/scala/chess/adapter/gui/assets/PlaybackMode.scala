package chess.adapter.gui.assets

/** Playback mode for a multi-segment sprite animation sequence.
  *
  * Applied to a full [[StatePlaybackMetadata]] sequence, not to individual sprite-sheet segments.
  */
enum PlaybackMode:

  /** Play through all segments once, then hold the final frame. Used for animations that should
    * stop: Move, Dead, Hit.
    */
  case Clamp

  /** Play through all segments in order, then wrap back to the first frame of the first segment.
    * Used for looping animations: Attack.
    */
  case Loop
