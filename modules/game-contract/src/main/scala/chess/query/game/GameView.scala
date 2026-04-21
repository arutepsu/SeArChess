package chess.application.query.game

import chess.application.session.model.SessionIds.GameId
import chess.domain.model.{Board, Color, GameStatus, Move, Piece, Position}
import chess.domain.rules.GameStateRules
import chess.domain.state.{CastlingRights, EnPassantState, GameState}

/** Contract read model for the current or final state of a Game Service game.
  *
  * This is intentionally not a REST DTO. Game Service maps it to transport payloads, while
  * downstream services can use it as a boundary snapshot.
  */
final case class GameView(
    gameId: GameId,
    currentPlayer: Color,
    status: GameStatus,
    board: Seq[(Position, Piece)],
    moveHistory: List[Move],
    castlingRights: CastlingRights,
    enPassantState: Option[EnPassantState],
    halfmoveClock: Int,
    fullmoveNumber: Int,
    legalMoves: Set[Move]
):
  /** Reconstruct a domain [[GameState]] from this view. */
  def toGameState: GameState =
    val reconstructedBoard = board.foldLeft(Board.empty) { case (b, (pos, piece)) =>
      b.place(pos, piece)
    }
    GameState(
      board = reconstructedBoard,
      currentPlayer = currentPlayer,
      moveHistory = moveHistory,
      status = status,
      castlingRights = castlingRights,
      enPassantState = enPassantState,
      halfmoveClock = halfmoveClock,
      fullmoveNumber = fullmoveNumber
    )

object GameView:
  def fromState(gameId: GameId, state: GameState): GameView =
    GameView(
      gameId = gameId,
      currentPlayer = state.currentPlayer,
      status = state.status,
      board = state.board.pieces,
      moveHistory = state.moveHistory,
      castlingRights = state.castlingRights,
      enPassantState = state.enPassantState,
      halfmoveClock = state.halfmoveClock,
      fullmoveNumber = state.fullmoveNumber,
      legalMoves = GameStateRules.legalMoves(state)
    )
