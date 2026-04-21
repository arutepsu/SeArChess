package chess.domain.state

import chess.domain.model.{Board, Color, GameStatus, Move}

final case class GameState(
    board: Board,
    currentPlayer: Color,
    moveHistory: List[Move],
    status: GameStatus,
    castlingRights: CastlingRights = CastlingRights.full,
    enPassantState: Option[EnPassantState] = None,
    halfmoveClock: Int = 0,
    fullmoveNumber: Int = 1
)
