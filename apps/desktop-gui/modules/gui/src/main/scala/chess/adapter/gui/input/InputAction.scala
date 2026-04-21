package chess.adapter.gui.input

import chess.domain.model.{PieceType, Position}

/** Semantic GUI events produced by input handlers and consumed by GameController. */
enum InputAction:
  case SquareClicked(position: Position)
  case PromotionPieceChosen(pieceType: PieceType)
  case ResetClicked
