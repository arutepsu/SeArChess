package chess.adapter.rest.contract.mapper

import chess.adapter.rest.contract.dto.{GameResponse, MoveHistoryEntry, PieceDto}
import chess.domain.model.GameStatus
import chess.domain.rules.GameStateRules
import chess.domain.state.GameState

/** Maps a [[GameState]] to a [[GameResponse]] DTO.
 *
 *  Derives all transport fields from the domain model; no chess logic
 *  is duplicated here.
 */
object GameMapper:

  /** Build a [[GameResponse]] from a [[GameState]] and its opaque id string.
   *
   *  @param promotionPending  forwarded directly into the DTO; callers set this
   *                           when a promotion choice is required before play
   *                           can continue.  Defaults to `false` (never true
   *                           in the current REST v1 error-driven promotion flow).
   */
  def toGameResponse(gameId: String, state: GameState, promotionPending: Boolean = false): GameResponse =
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

    val history = state.moveHistory.map { m =>
      MoveHistoryEntry(
        from      = m.from.toString,
        to        = m.to.toString,
        promotion = m.promotion.map(_.toString)
      )
    }

    val legalTargetsByFrom: Map[String, List[String]] =
      state.board.pieces
        .collect { case (pos, piece) if piece.color == state.currentPlayer => pos }
        .flatMap { pos =>
          val targets = GameStateRules.legalTargetsFrom(state, pos).map(_.toString).toList.sorted
          if targets.isEmpty then None
          else Some(pos.toString -> targets)
        }
        .toMap

    GameResponse(
      gameId             = gameId,
      currentPlayer      = state.currentPlayer.toString,
      status             = statusStr,
      inCheck            = inCheck,
      winner             = winner,
      drawReason         = drawReason,
      fullmoveNumber     = state.fullmoveNumber,
      halfmoveClock      = state.halfmoveClock,
      board              = board,
      moveHistory        = history,
      lastMove           = history.lastOption,
      promotionPending   = promotionPending,
      legalTargetsByFrom = legalTargetsByFrom
    )
