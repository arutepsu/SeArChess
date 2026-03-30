// $COVERAGE-OFF$
package chess.adapter.gui.assets

import chess.domain.model.Color
import scalafx.geometry.Pos
import scalafx.scene.Node
import scalafx.scene.image.{Image, ImageView}
import scalafx.scene.layout.StackPane
import scalafx.scene.paint.Color as FxColor
import scalafx.scene.text.{Font, Text}

/** Centralized piece-visual rendering helper.
 *
 *  This is the **single shared rendering path** for all piece visuals:
 *  - static board squares (`BoardRenderer`)
 *  - animation overlay (`ChessScene`)
 *  - promotion chooser (`PromotionOverlay`)
 *
 *  Internally it delegates to the full asset pipeline:
 *  {{{
 *    PieceVisualId
 *      → VisualResolver      (identity / asset key)
 *      → SpriteMetadataRepository (frame count / frame size)
 *      → SpriteSheetLoader   (PNG load, cache, frame slice)
 *      → ImageView           (primary output)
 *      fallback → Text glyph (when asset or metadata is absent)
 *  }}}
 *
 *  All fallback policy lives here — renderers and scenes never decide what
 *  to show when an asset is missing.
 *
 *  @param loader the shared [[SpriteSheetLoader]] instance (provides caching)
 */
class PieceNodeFactory(loader: SpriteSheetLoader, metaRepo: SpriteMetadataRepository):

  /** Build the visual content node for a piece.
   *
   *  Returns an `ImageView` when the sprite asset is available, or a glyph
   *  `Text` node as a fallback.  The node is not wrapped in a positioned
   *  container — callers add it as a child of their own layout pane.
   *
   *  Use for: static board squares, promotion buttons.
   *
   *  @param id               piece identity + visual state
   *  @param squareSize       board square size in logical pixels; governs image fit and glyph font
   *  @param frameIndex       zero-based frame to display (0 = first / only frame for static pieces)
   *  @param assetKeyOverride when [[Some]], use this asset key instead of the key derived from
   *                          [[id]] via [[VisualResolver]]; used for multi-segment states where the
   *                          active segment key differs from the primary key for the state
   *  @param flipX            when `true`, mirror the sprite horizontally; used for static piece
   *                          facing on the board (see [[chess.adapter.gui.render.PieceFacingPolicy]])
   */
  def content(
      id:               PieceVisualId,
      squareSize:       Double,
      frameIndex:       Int            = 0,
      assetKeyOverride: Option[String] = None,
      flipX:            Boolean        = false
  ): Node =
    val descriptor = VisualResolver.resolve(id)
    val resolvedKey = assetKeyOverride.getOrElse(descriptor.assetKey)
    val maybeImage = for
      meta  <- metaRepo.lookup(resolvedKey)
      image <- loader.loadFrame(resolvedKey, frameIndex, meta)
    yield
      val (dispW, dispH) = meta.displaySize.getOrElse((squareSize, squareSize))
      new ImageView(new Image(image)):
        fitWidth        = dispW
        fitHeight       = dispH
        preserveRatio   = false
    val node = maybeImage.getOrElse(fallbackGlyph(descriptor, id, squareSize))
    if flipX then node.scaleX = -1.0
    node

  /** Build a positioned, opacity-adjusted [[StackPane]] for the animation overlay.
   *
   *  Wraps [[content]] in a container with explicit `layoutX`/`layoutY` so it can
   *  be placed on the free-floating animation `Pane` over the board grid.
   *
   *  Use for: moving piece and captured-piece nodes in the animation layer.
   *
   *  @param id               piece identity + visual state
   *  @param x                board-local pixel x of the top-left corner
   *  @param y                board-local pixel y of the top-left corner
   *  @param squareSize       board square size in logical pixels
   *  @param opacity          1.0 = fully opaque; < 1.0 for capture fade-out
   *  @param frameIndex       frame to display
   *  @param assetKeyOverride when [[Some]], passed through to [[content]] to select
   *                          a specific segment asset for multi-segment states
   *  @param flipX            when `true`, mirror the sprite horizontally so the piece
   *                          faces left; the flip is applied to the content node and
   *                          keeps the piece centred within the container
   *  @param scale            uniform scale factor applied to the content node; `1.0` = normal;
   *                          the piece stays centred inside the container regardless of scale
   */
  def positioned(
      id:               PieceVisualId,
      x:                Double,
      y:                Double,
      squareSize:       Double,
      opacity:          Double         = 1.0,
      frameIndex:       Int            = 0,
      assetKeyOverride: Option[String] = None,
      flipX:            Boolean        = false,
      scale:            Double         = 1.0
  ): StackPane =
    // Build content before the StackPane block.  Set `opacity` after construction
    // because both `id` and `opacity` are parameter names that clash with Node
    // properties inherited inside anonymous class initializer blocks in ScalaFX.
    val pieceContent = content(id, squareSize, frameIndex, assetKeyOverride)
    pieceContent.scaleX = if flipX then -scale else scale
    pieceContent.scaleY = scale
    val pane = new StackPane:
      alignment  = Pos.Center
      prefWidth  = squareSize
      prefHeight = squareSize
      layoutX    = x
      layoutY    = y
      children   = Seq(pieceContent)
    pane.opacity = opacity
    pane

  // ── Private ─────────────────────────────────────────────────────────────────

  /** Unicode glyph fallback — rendered when no sprite asset is available. */
  private def fallbackGlyph(descriptor: VisualDescriptor, id: PieceVisualId, squareSize: Double): Text =
    // Capture `color` to avoid ambiguity with Node.id inside the ScalaFX initializer block.
    val isWhite = id.color == Color.White
    new Text:
      text        = descriptor.fallbackSymbol
      font        = Font("Segoe UI Symbol", squareSize * 0.62)
      fill        = if isWhite then FxColor.web("#fffffe") else FxColor.web("#1a1a1a")
      stroke      = if isWhite then FxColor.web("#333333") else FxColor.web("#cccccc")
      strokeWidth = 0.6
