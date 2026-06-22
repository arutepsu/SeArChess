package chess.userservice.domain

import java.time.Instant
import java.util.UUID

final case class TournamentBotOwnership(
  id: UUID,
  userId: UUID,
  botId: String,
  botName: String,
  createdAt: Instant
)
