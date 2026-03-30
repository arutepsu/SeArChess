package chess.adapter.gui.assets

/** Authoritative metadata for a single sprite-sheet asset.
 *
 *  This is the explicit, static source of truth for the structure of a PNG
 *  sprite sheet.  A future [[SpriteSheetLoader]] reads these values to
 *  locate frames inside the sheet.  No inference from image dimensions is
 *  used as the primary mechanism — all values must be declared here.
 *
 *  `frameSize` is required (not optional) because the loader needs it to
 *  slice frames correctly.  `displaySize` and `anchor` are optional
 *  overrides that let individual assets deviate from the default square-size
 *  rendering behaviour without touching any renderer code.
 *
 *  @param assetKey    resource key matching the [[chess.adapter.gui.assets.VisualDescriptor]] produced by
 *                     [[chess.adapter.gui.assets.VisualResolver]] (e.g. `"classic/white_king_idle"`)
 *  @param path        classpath-relative resource path to the PNG asset
 *                     (e.g. `"assets/classic/pawn/white_pawn_idle.png"`); used by
 *                     [[chess.adapter.gui.assets.SpriteSheetLoader]] to locate the file
 *  @param frameCount  total number of frames stacked vertically in the sprite sheet
 *  @param frameSize   native pixel dimensions of one frame `(width, height)`
 *  @param displaySize optional display-size override `(width, height)` in logical pixels;
 *                     [[None]] = use the surrounding board square size
 *  @param anchor      optional pivot as a fraction of [[displaySize]], `(0.0, 0.0)` = top-left;
 *                     [[None]] = top-left default
 */
final case class SpriteMetadata(
  assetKey:    String,
  path:        String,
  frameCount:  Int,
  frameSize:   (Int, Int),
  displaySize: Option[(Double, Double)],
  anchor:      Option[(Double, Double)]
)
