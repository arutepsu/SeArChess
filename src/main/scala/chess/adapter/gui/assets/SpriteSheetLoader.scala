// $COVERAGE-OFF$
package chess.adapter.gui.assets

import javafx.scene.image.{Image, WritableImage}

import scala.collection.mutable

/** Loads sprite-sheet PNG assets from the classpath, caches loaded sheets,
 *  and slices individual frames on demand.
 *
 *  === Resource path convention ===
 *  For a given [[SpriteMetadata.assetKey]] (e.g. `"classic/white_king_idle"`)
 *  the loader looks for `/assets/{assetKey}.png` on the classpath.
 *
 *  === Caching ===
 *  Full sprite sheets are cached by asset key after the first load so that
 *  repeated frame requests for the same sheet do not re-read the PNG.
 *  Individual frame images are not cached — [[WritableImage]] extraction is
 *  inexpensive compared to PNG decoding.
 *
 *  === Fallback ===
 *  When a PNG is absent from the classpath, [[loadFrame]] returns [[None]].
 *  The calling [[PieceNodeFactory]] falls back to the glyph path centrally —
 *  no fallback logic lives here.
 *
 *  === Frame layout ===
 *  Frames are assumed to be stacked vertically:
 *  frame 0 occupies rows `[0, frameHeight)`, frame 1 `[frameHeight, 2·frameHeight)`, etc.
 */
class SpriteSheetLoader:

  /** Cache keyed by asset key.  Value is [[None]] when the resource was absent
   *  on first load, so we do not re-attempt failed loads on every frame. */
  private val sheetCache = mutable.Map.empty[String, Option[Image]]

  /** Load frame [[frameIndex]] from the sprite sheet identified by [[assetKey]].
   *
   *  @param assetKey   matches [[SpriteMetadata.assetKey]]
   *  @param frameIndex zero-based index into the vertical frame stack
   *  @param meta       metadata supplying [[SpriteMetadata.frameSize]]
   *  @return           the sliced frame image, or [[None]] if the sheet could not be loaded
   */
  def loadFrame(assetKey: String, frameIndex: Int, meta: SpriteMetadata): Option[Image] =
    val sheet = sheetCache.getOrElseUpdate(assetKey, loadFromClasspath(assetKey))
    sheet.map(img => extractFrame(img, frameIndex, meta))

  // ── Private helpers ──────────────────────────────────────────────────────────

  private def loadFromClasspath(assetKey: String): Option[Image] =
    Option(getClass.getResourceAsStream(s"/assets/$assetKey.png"))
      .map(stream => new Image(stream))

  /** Extract one frame by slicing a vertical strip at `frameIndex * frameHeight`. */
  private def extractFrame(sheet: Image, frameIndex: Int, meta: SpriteMetadata): Image =
    val (fw, fh) = meta.frameSize
    new WritableImage(sheet.getPixelReader, 0, frameIndex * fh, fw, fh)
