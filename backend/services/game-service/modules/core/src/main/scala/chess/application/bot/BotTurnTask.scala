package chess.application.bot

import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.Color
import java.time.Instant
import java.util.UUID

enum BotTurnStatus:
  case Pending, Leased, Completed, Failed

final case class BotTurnTask(
    id: UUID,
    sessionId: SessionId,
    gameId: GameId,
    botActorId: String,
    sideToMove: Color,
    status: BotTurnStatus,
    leaseUntil: Option[Instant],
    attemptCount: Int,
    lastError: Option[String],
    createdAt: Instant,
    updatedAt: Instant
)
