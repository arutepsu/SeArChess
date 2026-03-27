package chess.adapter.gui.assets

/** Metadata describing a single visual piece asset.
 *
 *  All fields are fully resolved by [[VisualResolver]] from a [[PieceVisualId]].
 *  Renderers consume this value without knowing anything about asset naming
 *  rules, themes, or fallback policy.
 *
 *  === Current usage ===
 *  Real sprite assets do not exist yet.  [[fallbackSymbol]] provides a Unicode
 *  chess glyph that renderers use in their place.  All other fields are set to
 *  placeholder values (`frameCount = 1`, optional fields = [[None]]).
 *
 *  === Future usage ===
 *  When real sprites are available a loader will read [[assetKey]] to locate
 *  the resource and use [[frameCount]] / [[frameSize]] to extract frames from
 *  the sprite sheet.  [[displaySize]] and [[anchor]] let individual assets
 *  override the default square-size rendering without touching renderer code.
 *
 *  @param assetKey       resource key / path fragment used by a future loader
 *                        (e.g. `"classic/white_king_idle"`); never empty
 *  @param frameCount     number of animation frames; 1 for static images
 *  @param frameSize      native pixel dimensions of one frame `(width, height)`;
 *                        [[None]] until actual sprite assets are measured
 *  @param displaySize    optional display-size override `(width, height)` in
 *                        logical pixels; [[None]] = use the board square size
 *  @param anchor         optional pivot point as a fraction of [[displaySize]],
 *                        `(0.0, 0.0)` = top-left; [[None]] = top-left default
 *  @param fallbackSymbol Unicode glyph to render while real assets are absent
 */
final case class VisualDescriptor(
  assetKey:       String,
  frameCount:     Int,
  frameSize:      Option[(Int, Int)],
  displaySize:    Option[(Double, Double)],
  anchor:         Option[(Double, Double)],
  fallbackSymbol: String
)
