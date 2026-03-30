package chess.adapter.gui.assets

/** Identity bridge between [[VisualResolver]] and the asset pipeline.
 *
 *  Carries the two values that flow from [[VisualResolver]] to [[PieceNodeFactory]]:
 *
 *  - [[assetKey]] — the resource key used to locate the sprite sheet via
 *    [[SpriteMetadataRepository]] and [[SpriteSheetLoader]]
 *  - [[fallbackSymbol]] — a Unicode chess glyph used when the sprite asset is
 *    absent; the fallback decision lives in [[PieceNodeFactory]], not in renderers
 *
 *  Sprite-sheet structure (frame count, frame size, display size, anchor) is
 *  **not** stored here — it lives in [[SpriteMetadata]] and is looked up by
 *  [[SpriteMetadataRepository]].  This keeps the resolver purely concerned with
 *  identity and naming, independent of physical asset properties.
 *
 *  @param assetKey       resource key, e.g. `"classic/white_king_idle"`
 *  @param fallbackSymbol Unicode glyph for the piece (state-independent)
 */
final case class VisualDescriptor(
  assetKey:       String,
  fallbackSymbol: String
)
