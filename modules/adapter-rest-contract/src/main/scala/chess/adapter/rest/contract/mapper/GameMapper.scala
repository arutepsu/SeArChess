package chess.adapter.rest.contract.mapper

import chess.adapter.rest.contract.dto.{GameResponse, LegalMoveDto, LegalMovesResponse, MoveHistoryEntry, PieceDto}
import chess.application.query.game.{GameView, LegalMovesView}
import chess.domain.model.GameStatus

/** Maps a [[GameView]] to a [[GameResponse]] DTO.
 *
 *  Derives all transport fields from the application read model; no domain
 *  rules are invoked here.  Legal-move derivation is pre-computed in
 *  [[GameView.fromState]] and carried through [[GameView.legalMoves]].
 */
object GameMapper:

  /** Build a [[GameResponse]] from a [[GameView]].
   *
   *  @param view              application read model for the game position
   *  @param promotionPending  forwarded directly into the DTO; callers set this
   *                           when a promotion choice is required before play
   *                           can continue.  Defaults to `false` (never true
   *                           in the current REST v1 error-driven promotion flow).
   */
  def toGameResponse(view: GameView, promotionPending: Boolean = false): GameResponse =
    val (statusStr, inCheck, winner, drawReason) = view.status match
      case GameStatus.Ongoing(check)    => ("Ongoing",   check, None,                   None)
      case GameStatus.Checkmate(winner) => ("Checkmate", false, Some(winner.toString),  None)
      case GameStatus.Draw(reason)      => ("Draw",      false, None,                   Some(reason.toString))
      case GameStatus.Resigned(winner)  => ("Resigned",  false, Some(winner.toString),  None)

    val board = view.board
      .map { case (pos, piece) =>
        PieceDto(
          square    = pos.toString,
          color     = piece.color.toString,
          pieceType = piece.pieceType.toString
        )
      }
      .toList

    val history = view.moveHistory.map { m =>
      MoveHistoryEntry(
        from      = m.from.toString,
        to        = m.to.toString,
        promotion = m.promotion.map(_.toString)
      )
    }

    val legalTargetsByFrom: Map[String, List[String]] =
      view.legalMoves
        .groupBy(_.from.toString)
        .view
        .mapValues(_.map(_.to.toString).toList.sorted)
        .toMap

    GameResponse(
      gameId             = view.gameId.value.toString,
      currentPlayer      = view.currentPlayer.toString,
      status             = statusStr,
      inCheck            = inCheck,
      winner             = winner,
      drawReason         = drawReason,
      fullmoveNumber     = view.fullmoveNumber,
      halfmoveClock      = view.halfmoveClock,
      board              = board,
      moveHistory        = history,
      lastMove           = history.lastOption,
      promotionPending   = promotionPending,
      legalTargetsByFrom = legalTargetsByFrom
    )

  /** Build a first-class legal-moves response from the Game Service query view. */
  def toLegalMovesResponse(view: LegalMovesView): LegalMovesResponse =
    val orderedMoves = view.moves.toList.sortBy(m =>
      (m.from.file, m.from.rank, m.to.file, m.to.rank, m.promotion.map(_.toString).getOrElse("")))

    val moveDtos = orderedMoves.map { m =>
      LegalMoveDto(
        from      = m.from.toString,
        to        = m.to.toString,
        promotion = m.promotion.map(_.toString)
      )
    }

    val targetsByFrom = orderedMoves
      .groupBy(_.from.toString)
      .view
      .mapValues(_.map(_.to.toString).distinct.sorted)
      .toMap

    LegalMovesResponse(
      gameId             = view.gameId.value.toString,
      currentPlayer      = view.currentPlayer.toString,
      moves              = moveDtos,
      legalTargetsByFrom = targetsByFrom
    )
