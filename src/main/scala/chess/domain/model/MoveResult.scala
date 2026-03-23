package chess.domain.model

enum MoveResult:
  case Applied(board: Board)
  case PromotionRequired(board: Board, square: Position, color: Color)
