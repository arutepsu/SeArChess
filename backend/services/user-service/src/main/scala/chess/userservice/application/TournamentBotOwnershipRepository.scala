package chess.userservice.application

import chess.userservice.domain.TournamentBotOwnership
import java.util.UUID

trait TournamentBotOwnershipRepository:
  def findAllByUserId(userId: UUID): Either[String, List[TournamentBotOwnership]]
  def insertIfAbsent(ownership: TournamentBotOwnership): Either[String, TournamentBotOwnership]
