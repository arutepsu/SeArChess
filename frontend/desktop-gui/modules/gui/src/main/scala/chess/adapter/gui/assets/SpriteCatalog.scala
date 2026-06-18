package chess.adapter.gui.assets

/** Validated, fully-resolved in-memory representation of the sprite asset catalog loaded from
  * `sprite_catalog.json`.
  *
  * All cross-section references (clipSpec → spriteSheet, segment → spriteSheet) are validated and
  * inlined by [[SpriteCatalogParser]] before this value is produced. Consumers never need to
  * re-validate or re-resolve references.
  *
  * @param theme
  *   catalog theme identifier (e.g. `"classic"`)
  * @param spriteSheets
  *   sprite sheets keyed by logical asset key
  * @param statePlayback
  *   playback definitions keyed by primary asset key
  */
final case class SpriteCatalog(
    theme: String,
    spriteSheets: Map[String, SpriteSheetEntry],
    statePlayback: Map[String, StatePlaybackEntry]
)

/** Resolved metadata for one sprite sheet, with [[clipSpec]] inlined from the catalog's `clipSpecs`
  * section.
  *
  * @param assetKey
  *   logical key used throughout the asset subsystem (e.g. `"classic/white_pawn_move"`)
  * @param path
  *   classpath-relative resource path to the PNG (e.g. `"assets/classic/pawn/white_pawn_move.png"`)
  * @param clipSpec
  *   frame geometry and display overrides for this sheet
  */
final case class SpriteSheetEntry(
    assetKey: String,
    path: String,
    clipSpec: ClipSpecEntry
)

/** Frame geometry shared by both colour variants of one piece clip.
  *
  * @param frameCount
  *   total number of frames stacked vertically in the sheet
  * @param frameSize
  *   native pixel dimensions of one frame `(width, height)`
  * @param displaySize
  *   optional display-size override in logical pixels
  * @param anchor
  *   optional pivot as a fraction of [[displaySize]]
  */
final case class ClipSpecEntry(
    frameCount: Int,
    frameSize: (Int, Int),
    displaySize: Option[(Double, Double)],
    anchor: Option[(Double, Double)]
)

/** Logical state playback definition — one validated entry from the `statePlayback` section.
  *
  * @param state
  *   the [[VisualState]] this definition belongs to
  * @param mode
  *   [[PlaybackMode]] applied to the complete segment sequence
  * @param segments
  *   ordered asset keys of the sprite-sheet segments; every key has been validated against the
  *   `spriteSheets` section
  */
final case class StatePlaybackEntry(
    state: VisualState,
    mode: PlaybackMode,
    segments: Seq[String]
)
