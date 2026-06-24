package chess.userservice.application

import chess.userservice.domain.PublicTournamentHostRecord

trait PublicTournamentHostRepository:
  def findByTournamentId(tournamentId: String): Either[String, Option[PublicTournamentHostRecord]]
  def insertIfAbsent(record: PublicTournamentHostRecord): Either[String, PublicTournamentHostRecord]
