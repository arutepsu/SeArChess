package chess.adapter.gui.assets

import chess.domain.model.{Color, PieceType}

/** Pure mapping from [[PieceVisualId]] to [[VisualDescriptor]].
  *
  * This is the single authoritative place for asset-key policy:
  *   - asset key / resource path conventions
  *   - fallback glyph selection (delegated to [[PieceSymbol]])
  *
  * Sprite-sheet structure (frame counts, frame sizes) lives in [[SpriteMetadataRepository]], not
  * here.
  *
  * [[VisualResolver]] has no ScalaFX dependency and no mutable state. Renderers must not build
  * asset keys or choose glyphs directly — they consume the descriptor returned here.
  *
  * ===Asset key convention===
  * `"{theme}/{color}_{pieceType}_{state}"`, e.g. `"classic/white_king_idle"`. The theme segment
  * (`"classic"`) is the one implemented theme for this phase. Future themes are introduced by
  * adding a `theme` parameter or a lookup table here — no renderer changes are required.
  */
object VisualResolver:

  /** Resolve the [[VisualDescriptor]] for the given [[id]].
    *
    * Always returns a valid descriptor. Fallback behaviour (glyph rendering) is encapsulated in
    * [[VisualDescriptor.fallbackSymbol]].
    */
  def resolve(id: PieceVisualId): VisualDescriptor =
    val colorKey = id.color match
      case Color.White => "white"
      case Color.Black => "black"

    val pieceKey = id.pieceType match
      case PieceType.King   => "king"
      case PieceType.Queen  => "queen"
      case PieceType.Rook   => "rook"
      case PieceType.Bishop => "bishop"
      case PieceType.Knight => "knight"
      case PieceType.Pawn   => "pawn"

    val stateKey = id.state match
      case VisualState.Idle   => "idle"
      case VisualState.Move   => "move"
      case VisualState.Attack => "attack"
      case VisualState.Hit    => "hit"
      case VisualState.Dead   => "dead"

    VisualDescriptor(
      assetKey = s"classic/${colorKey}_${pieceKey}_${stateKey}",
      fallbackSymbol = PieceSymbol.symbol(id.color, id.pieceType)
    )
