package chess.userservice.domain

import java.time.Instant
import java.util.UUID

final case class PublicTournamentParticipant(
  tournamentId: String,
  botId: String,
  botName: String,
  userId: UUID,
  displayName: String,
  joinedAt: Instant
)
