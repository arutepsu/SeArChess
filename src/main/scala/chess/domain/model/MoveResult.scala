package chess.domain.model

sealed trait MoveResult
object MoveResult:
  final case class Applied(board: Board)                                          extends MoveResult
  final case class PromotionRequired(board: Board, square: Position, color: Color) extends MoveResult
