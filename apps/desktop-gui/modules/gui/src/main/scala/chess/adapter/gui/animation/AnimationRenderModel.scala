package chess.adapter.gui.animation

import chess.adapter.gui.assets.VisualState
import chess.domain.model.{Color, PieceType, Position}

/** Renderer-facing description of a single animation frame.
  *
  * All values are fully resolved (interpolated, faded, decided) by [[AnimationPresentationMapper]]
  * so that renderers and scene code can draw directly without knowing animation semantics.
  *
  * Coordinates are board-local pixels with the top-left origin at rank 7 (top edge of the board),
  * matching the on-screen layout.
  *
  * @param movingPiece
  *   the piece currently travelling from source to destination
  * @param capturedPiece
  *   the captured piece and its current opacity if it should still be visible; [[None]] once it has
  *   faded out
  * @param suppressedSquare
  *   the square whose static piece must not be drawn by the board renderer (the animation overlay
  *   draws it instead)
  */
final case class AnimationRenderModel(
    movingPiece: PieceRenderInfo,
    capturedPiece: Option[PieceRenderInfo],
    suppressedSquare: Option[Position]
)

/** Position and appearance of one piece for a single animation frame.
  *
  * @param piece
  *   colour and piece type
  * @param x
  *   top-left pixel x within the board area (0 = left edge, file 0)
  * @param y
  *   top-left pixel y within the board area (0 = top edge, rank 7)
  * @param opacity
  *   1.0 = fully opaque; decreases for the captured-piece fade-out
  * @param frameIndex
  *   zero-based sprite-sheet frame to display; computed by
  *   [[chess.adapter.gui.assets.SequencePlaybackPolicy]] in [[AnimationPresentationMapper]];
  *   defaults to 0 for static pieces
  * @param segmentAssetKey
  *   asset key of the specific sprite-sheet segment to display; overrides the default key derived
  *   from [[PieceVisualId]] when set (used for multi-segment states such as Attack); [[None]] means
  *   use the primary key for the state
  * @param flipX
  *   when `true` the sprite should be rendered mirrored horizontally; used to face the piece in the
  *   direction of travel
  * @param visualState
  *   the actual [[VisualState]] being rendered; used by the scene to build the correct
  *   [[chess.adapter.gui.assets.PieceVisualId]]
  * @param scale
  *   uniform scale factor applied to the sprite; `1.0` = normal size; values above `1.0` produce a
  *   momentary pop effect (e.g. hit reaction)
  */
final case class PieceRenderInfo(
    piece: (Color, PieceType),
    x: Double,
    y: Double,
    visualState: VisualState,
    opacity: Double = 1.0,
    frameIndex: Int = 0,
    segmentAssetKey: Option[String] = None,
    flipX: Boolean = false,
    scale: Double = 1.0
)
