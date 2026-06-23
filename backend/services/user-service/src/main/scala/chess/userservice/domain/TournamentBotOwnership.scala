package chess.userservice.domain

import java.time.Instant
import java.util.UUID

final case class TournamentBotOwnership(
  id:                    UUID,
  userId:                UUID,
  tournamentServerBotId:   String,
  tournamentServerBotName: String,
  searchessCatalogBotId:   Option[String], // None for custom bots; Some(catalogId) for Searchess catalog bots
  createdAt:             Instant
)
