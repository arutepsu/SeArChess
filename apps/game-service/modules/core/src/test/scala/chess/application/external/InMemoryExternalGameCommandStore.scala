package chess.application.external

import chess.application.port.repository.{ExternalGameCommandStore, RepositoryError}
import chess.application.port.repository.{GameRepository, SessionRepository}
import chess.application.session.model.GameSession
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.state.GameState
import java.time.Instant
import scala.collection.mutable

/** Minimal [[SessionRepository]] for use in external-game service tests only. */
class StubSessionRepository extends SessionRepository:
  val store: mutable.HashMap[SessionId, GameSession] = mutable.HashMap.empty
  private val byGameId: mutable.HashMap[GameId, SessionId] = mutable.HashMap.empty

  def save(s: GameSession): Either[RepositoryError, Unit] =
    store(s.sessionId) = s
    byGameId(s.gameId) = s.sessionId
    Right(())
  def load(id: SessionId): Either[RepositoryError, GameSession] =
    store.get(id).toRight(RepositoryError.NotFound(id.value.toString))
  def loadByGameId(id: GameId): Either[RepositoryError, GameSession] =
    byGameId.get(id).flatMap(store.get).toRight(RepositoryError.NotFound(id.value.toString))
  def listActive(): Either[RepositoryError, List[GameSession]] =
    Right(store.values.toList)

/** Minimal [[GameRepository]] for use in external-game service tests only. */
class StubGameRepository extends GameRepository:
  val store: mutable.HashMap[GameId, GameState] = mutable.HashMap.empty

  def load(id: GameId): Either[RepositoryError, GameState] =
    store.get(id).toRight(RepositoryError.NotFound(id.value.toString))
  def save(id: GameId, state: GameState): Either[RepositoryError, Unit] =
    store(id) = state; Right(())

/** In-memory [[ExternalGameCommandStore]] for use in tests.
  *
  * Performs sequential writes without ACID guarantees — acceptable for single-threaded tests where
  * atomicity failure windows are irrelevant.
  */
class InMemoryExternalGameCommandStore(
    sessionRepo: SessionRepository,
    gameRepo: GameRepository,
    bindingRepo: InMemoryExternalGameBindingRepository
) extends ExternalGameCommandStore:

  override def createWithBinding(
      session: GameSession,
      state: GameState,
      binding: ExternalGameBinding
  ): Either[RepositoryError, Unit] =
    for
      _ <- sessionRepo.save(session)
      _ <- gameRepo.save(session.gameId, state)
      _ <- bindingRepo.create(binding)
    yield ()

  override def saveMoveWithPlyAdvance(
      session: GameSession,
      state: GameState,
      bindingId: BindingId,
      newPly: Int,
      now: Instant
  ): Either[RepositoryError, Unit] =
    for
      _ <- sessionRepo.save(session)
      _ <- gameRepo.save(session.gameId, state)
      _ <- bindingRepo.advancePly(bindingId, newPly, now)
    yield ()

  override def saveFinishedWithBinding(
      session: GameSession,
      state: GameState,
      bindingId: BindingId,
      now: Instant
  ): Either[RepositoryError, Unit] =
    for
      _ <- sessionRepo.save(session)
      _ <- gameRepo.save(session.gameId, state)
      _ <- bindingRepo.markFinished(bindingId, now)
    yield ()
