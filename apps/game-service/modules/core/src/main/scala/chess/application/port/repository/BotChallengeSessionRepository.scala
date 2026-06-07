package chess.application.port.repository

import chess.application.bot.BotChallengeSession

import java.util.UUID

trait BotChallengeSessionRepository:
  def save(session: BotChallengeSession): Either[RepositoryError, Unit]
  def update(session: BotChallengeSession): Either[RepositoryError, Unit]
  def findById(id: UUID): Either[RepositoryError, Option[BotChallengeSession]]
