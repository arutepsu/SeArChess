package chess.adapter.repository

import chess.application.bot.BotChallengeSession
import chess.application.port.repository.{BotChallengeSessionRepository, RepositoryError}

import java.util.UUID
import scala.collection.concurrent.TrieMap

class InMemoryBotChallengeSessionRepository extends BotChallengeSessionRepository:
  private val store = TrieMap.empty[UUID, BotChallengeSession]

  override def save(session: BotChallengeSession): Either[RepositoryError, Unit] =
    store.put(session.id, session)
    Right(())

  override def update(session: BotChallengeSession): Either[RepositoryError, Unit] =
    store.put(session.id, session)
    Right(())

  override def findById(id: UUID): Either[RepositoryError, Option[BotChallengeSession]] =
    Right(store.get(id))

  def all: List[BotChallengeSession] =
    store.values.toList

object InMemoryBotChallengeSessionRepository:
  def apply(): InMemoryBotChallengeSessionRepository = new InMemoryBotChallengeSessionRepository
