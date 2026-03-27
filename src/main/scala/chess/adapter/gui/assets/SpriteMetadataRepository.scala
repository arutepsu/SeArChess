package chess.adapter.gui.assets

/** Static metadata repository for all known sprite-sheet assets.
 *
 *  Holds authoritative [[SpriteMetadata]] for every `(color, pieceType, state)`
 *  combination supported by the current theme.  This is the single place where
 *  frame counts, frame sizes, and per-asset display overrides are declared.
 *
 *  === Placeholder values ===
 *  Real PNG assets do not yet exist.  All frame sizes are set to a uniform
 *  placeholder of 64 × 64 px.  Frame counts vary by state to exercise the
 *  variable-count design that will be needed once real sprites are measured:
 *
 *  {{{
 *    Idle   → 1 frame   (standing still)
 *    Move   → 4 frames  (short travel cycle)
 *    Attack → 6 frames  (swing / shoot cycle)
 *    Hit    → 3 frames  (knockback flash)
 *    Dead   → 8 frames  (collapse animation)
 *  }}}
 *
 *  When real assets arrive, update [[frameCountByState]] and [[DefaultFrameSize]]
 *  (or add per-key overrides) here — no renderer changes are required.
 *
 *  === Lookup ===
 *  [[lookup]] returns [[None]] for any key that is not in the table.
 *  The centralized fallback in [[PieceNodeFactory]] handles missing entries.
 */
object SpriteMetadataRepository:

  /** Frame counts per visual state — uniform across piece types for this phase. */
  private val frameCountByState: Map[String, Int] = Map(
    "idle"   -> 1,
    "move"   -> 4,
    "attack" -> 6,
    "hit"    -> 3,
    "dead"   -> 8
  )

  /** Native frame pixel size placeholder — updated when real assets are measured. */
  private val DefaultFrameSize: (Int, Int) = (64, 64)

  private val metadata: Map[String, SpriteMetadata] = (
    for
      color     <- Seq("white", "black")
      pieceType <- Seq("king", "queen", "rook", "bishop", "knight", "pawn")
      state     <- Seq("idle", "move", "attack", "hit", "dead")
    yield
      val key = s"classic/${color}_${pieceType}_${state}"
      key -> SpriteMetadata(
        assetKey    = key,
        frameCount  = frameCountByState(state),
        frameSize   = DefaultFrameSize,
        displaySize = None,
        anchor      = None
      )
  ).toMap

  /** Return the [[SpriteMetadata]] for the given asset key, or [[None]] if unknown. */
  def lookup(assetKey: String): Option[SpriteMetadata] = metadata.get(assetKey)
