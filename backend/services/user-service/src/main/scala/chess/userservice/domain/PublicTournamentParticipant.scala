package chess.userservice.domain

import java.time.Instant
import java.util.UUID

final case class PublicTournamentParticipant(
  tournamentId:            String,
  searchessUserId:         UUID,
  displayName:             String,
  searchessBotId:          Option[UUID],   // None for rows that predate V10 migration
  tournamentServerUserId:  Option[String], // None for rows that predate V10 migration
  tournamentServerBotId:   String,
  tournamentServerBotName: String,
  joinedAt:                Instant
)
