package chess.userservice.domain

import java.time.Instant
import java.util.UUID

final case class PublicTournamentHostRecord(
  tournamentId:                   String,
  hostSearchessUserId:             UUID,
  hostKeycloakSub:                 String,
  hostDisplayName:                 String,
  createdAt:                       Instant,
  directorTournamentServerBotId:   Option[String],
  directorTournamentServerBotName: Option[String]
)
