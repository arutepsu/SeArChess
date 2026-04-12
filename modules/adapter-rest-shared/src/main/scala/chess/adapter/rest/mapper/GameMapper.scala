package chess.adapter.rest.mapper

import chess.adapter.rest.dto.{GameResponse, PieceDto}
import chess.domain.model.GameStatus
import chess.domain.state.GameState

/** Maps a [[GameState]] to a [[GameResponse]] DTO.
 *
 *  Derives all transport fields from the domain model; no chess logic
 *  is duplicated here.
 */
object GameMapper:

  /** Build a [[GameResponse]] from a [[GameState]] and its opaque id string. */
  def toGameResponse(gameId: String, state: GameState): GameResponse =
    val (statusStr, inCheck, winner, drawReason) = state.status match
      case GameStatus.Ongoing(check)    => ("Ongoing",   check, None,                   None)
      case GameStatus.Checkmate(winner) => ("Checkmate", false, Some(winner.toString),  None)
      case GameStatus.Draw(reason)      => ("Draw",      false, None,                   Some(reason.toString))

    val board = state.board.pieces
      .map { case (pos, piece) =>
        PieceDto(
          square    = pos.toString,
          color     = piece.color.toString,
          pieceType = piece.pieceType.toString
        )
      }
      .toList

    GameResponse(
      gameId         = gameId,
      currentPlayer  = state.currentPlayer.toString,
      status         = statusStr,
      inCheck        = inCheck,
      winner         = winner,
      drawReason     = drawReason,
      fullmoveNumber = state.fullmoveNumber,
      halfmoveClock  = state.halfmoveClock,
      board          = board
    )
