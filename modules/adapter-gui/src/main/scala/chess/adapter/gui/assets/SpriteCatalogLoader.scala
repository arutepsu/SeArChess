// $COVERAGE-OFF$
package chess.adapter.gui.assets

/** Loads the sprite catalog from the classpath and validates it.
 *
 *  This is the impure entry point for the catalog pipeline.  All JSON
 *  parsing and validation logic lives in the pure [[SpriteCatalogParser]].
 *
 *  The catalog is expected at `/assets/sprite_catalog.json` on the classpath
 *  (i.e. `src/main/resources/assets/sprite_catalog.json` in the project).
 *
 *  === Failure behaviour ===
 *  If the file is absent or the catalog fails validation, an
 *  `IllegalStateException` is thrown immediately.  This is intentional:
 *  a missing or invalid catalog is a programming error, not a recoverable
 *  runtime condition.
 */
object SpriteCatalogLoader:

  private val CatalogPath = "/assets/sprite_catalog.json"

  /** Load, parse, and validate the catalog.
   *
   *  @return the validated [[SpriteCatalog]]
   *  @throws IllegalStateException if the file is absent or the catalog
   *          fails validation
   */
  def load(): SpriteCatalog =
    val stream = getClass.getResourceAsStream(CatalogPath)
    if stream == null then
      throw IllegalStateException(s"Sprite catalog not found at $CatalogPath")
    val json = String(stream.readAllBytes(), "UTF-8")
    SpriteCatalogParser.parse(json) match
      case Right(catalog) => catalog
      case Left(errors)   =>
        throw IllegalStateException(
          s"Sprite catalog validation failed:\n${errors.mkString("\n")}")
