package chess.userservice.domain

import java.time.Instant
import java.util.UUID

final case class PublicTournamentHostRecord(
  tournamentId:        String,
  hostSearchessUserId: UUID,
  hostDisplayName:     String,
  createdAt:           Instant
)
