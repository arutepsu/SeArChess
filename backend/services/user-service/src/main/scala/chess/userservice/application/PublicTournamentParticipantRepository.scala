package chess.userservice.application

import chess.userservice.domain.PublicTournamentParticipant

trait PublicTournamentParticipantRepository:
  def findByTournamentId(tournamentId: String): Either[String, List[PublicTournamentParticipant]]
  def insertIfAbsent(participant: PublicTournamentParticipant): Either[String, PublicTournamentParticipant]
