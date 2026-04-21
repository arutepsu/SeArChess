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
 *  When a PNG is absent from the classpath, or the requested frame lies outside
 *  the actual image bounds, [[loadFrame]] returns [[None]].
 *  The calling [[PieceNodeFactory]] falls back to the glyph path centrally —
 *  no fallback logic lives here.
 *
 *  === Frame layout ===
 *  Frames are arranged left-to-right in a single row (horizontal strip):
 *  frame 0 occupies columns `[0, frameWidth)`, frame 1 `[frameWidth, 2·frameWidth)`, etc.
 *  All frames share the same height; the sheet height equals one frame height.
 */
class SpriteSheetLoader:

  /** Cache keyed by resource path.  Value is [[None]] when the resource was absent
   *  on first load, so we do not re-attempt failed loads on every frame. */
  private val sheetCache = mutable.Map.empty[String, Option[Image]]

  /** Load frame [[frameIndex]] from the sprite sheet identified by [[meta.path]].
   *
   *  @param assetKey   logical asset key (used only for the cache; the actual
   *                    classpath path comes from [[meta.path]])
   *  @param frameIndex zero-based index into the horizontal frame strip
   *  @param meta       metadata supplying the classpath path and frame size
   *  @return           the sliced frame image, or [[None]] if the sheet could not
   *                    be loaded or if the frame lies outside the image bounds
   */
  def loadFrame(assetKey: String, frameIndex: Int, meta: SpriteMetadata): Option[Image] =
    val sheet = sheetCache.getOrElseUpdate(meta.path, loadFromClasspath(meta.path))
    sheet.flatMap(img => extractFrame(img, frameIndex, meta, assetKey))

  // ── Private helpers ──────────────────────────────────────────────────────────

  private def loadFromClasspath(path: String): Option[Image] =
    Option(getClass.getResourceAsStream(s"/$path"))
      .map(stream => new Image(stream))

  /** Returns `true` when the frame slice is fully within the loaded image. */
  private def isFrameValid(sheet: Image, frameIndex: Int, meta: SpriteMetadata): Boolean =
    val (fw, fh) = meta.frameSize
    frameIndex >= 0 &&
    frameIndex < meta.frameCount &&
    fw > 0 &&
    fh > 0 &&
    sheet.getHeight >= fh &&
    sheet.getWidth  >= (frameIndex + 1) * fw

  /** Extract one frame by slicing a horizontal strip at `frameIndex * frameWidth`.
   *  Returns [[None]] and logs a warning when the slice would exceed image bounds. */
  private def extractFrame(
      sheet:      Image,
      frameIndex: Int,
      meta:       SpriteMetadata,
      assetKey:   String
  ): Option[Image] =
    if isFrameValid(sheet, frameIndex, meta) then
      val (fw, fh) = meta.frameSize
      Some(new WritableImage(sheet.getPixelReader, frameIndex * fw, 0, fw, fh))
    else
      val (fw, fh) = meta.frameSize
      System.err.println(
        s"[SpriteSheetLoader] Frame out of bounds — " +
        s"assetKey=$assetKey path=${meta.path} " +
        s"frameIndex=$frameIndex frameCount=${meta.frameCount} " +
        s"frameSize=(${fw}x${fh}) " +
        s"sheetSize=(${sheet.getWidth.toInt}x${sheet.getHeight.toInt})"
      )
      None
