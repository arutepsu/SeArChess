package chess.adapter.textui

import chess.domain.model.PieceType

enum TextUiCommand:
  case New
  case MoveCmd(from: String, to: String)
  case PromoteCmd(pieceType: PieceType)
  case Show
  case Help
  case Quit
