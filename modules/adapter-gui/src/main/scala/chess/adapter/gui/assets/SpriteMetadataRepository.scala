package chess.adapter.gui.assets

/** Repository of [[SpriteMetadata]] entries for all known sprite-sheet assets.
 *
 *  In production use, instances are created via [[SpriteMetadataRepository.fromCatalog]]
 *  which derives all metadata from the validated [[SpriteCatalog]].  Test code
 *  may construct instances directly with an explicit entry map.
 *
 *  Frame counts, frame sizes, and resource paths come exclusively from the
 *  catalog — no values are hardcoded here.
 *
 *  @param entries metadata map keyed by logical asset key
 */
final class SpriteMetadataRepository(private val entries: Map[String, SpriteMetadata]):

  /** Return the [[SpriteMetadata]] for the given asset key, or [[None]] if
   *  the key is not in the repository.
   *
   *  A [[None]] result is handled centrally by [[chess.adapter.gui.assets.PieceNodeFactory]]
   *  via its glyph fallback path — callers do not need their own fallback.
   */
  def lookup(assetKey: String): Option[SpriteMetadata] = entries.get(assetKey)

/** Companion providing the catalog-backed factory.
 *
 *  All frame counts, frame sizes, and paths are resolved from the
 *  [[SpriteCatalog]] produced by [[SpriteCatalogParser]].
 */
object SpriteMetadataRepository:

  /** Build a [[SpriteMetadataRepository]] from a validated [[SpriteCatalog]].
   *
   *  One [[SpriteMetadata]] entry is created for every entry in
   *  `catalog.spriteSheets`, inlining the [[ClipSpecEntry]] data.
   */
  def fromCatalog(catalog: SpriteCatalog): SpriteMetadataRepository =
    val entries = catalog.spriteSheets.map { (key, sheet) =>
      val spec = sheet.clipSpec
      key -> SpriteMetadata(
        assetKey    = key,
        path        = sheet.path,
        frameCount  = spec.frameCount,
        frameSize   = spec.frameSize,
        displaySize = spec.displaySize,
        anchor      = spec.anchor
      )
    }
    SpriteMetadataRepository(entries)
