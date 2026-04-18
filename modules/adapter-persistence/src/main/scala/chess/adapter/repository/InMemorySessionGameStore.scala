package chess.adapter.repository

import chess.application.port.repository.{RepositoryError, SessionGameStore}
import chess.application.session.model.GameSession
import chess.domain.state.GameState

/** In-memory implementation of [[SessionGameStore]].
 *
 *  Delegates to the existing [[InMemorySessionRepository]] and
 *  [[InMemoryGameRepository]] in sequence.  Both writes succeed in practice
 *  (in-memory HashMaps never fail), so this implementation is effectively
 *  atomic for unit tests and the desktop composition root.
 *
 *  A future JDBC-backed implementation would wrap both writes in a single
 *  transaction to provide true atomicity under concurrent writes or process
 *  failures.
 *
 *  @param sessionRepo session-side in-memory store
 *  @param gameRepo    game-state in-memory store
 */
class InMemorySessionGameStore(
  sessionRepo: InMemorySessionRepository,
  gameRepo:    InMemoryGameRepository
) extends SessionGameStore:

  override def save(session: GameSession, state: GameState): Either[RepositoryError, Unit] =
    for
      _ <- sessionRepo.save(session)
      _ <- gameRepo.save(session.gameId, state)
    yield ()

  override def undo(
    gameId: chess.application.session.model.SessionIds.GameId,
    nextSession: GameState => GameSession
  ): Either[RepositoryError, (GameState, GameSession)] =
    for
      restoredState <- gameRepo.undo(gameId)
      sessionToSave = nextSession(restoredState)
      _ <- sessionRepo.save(sessionToSave)
    yield (restoredState, sessionToSave)

  override def redo(
    gameId: chess.application.session.model.SessionIds.GameId,
    nextSession: GameState => GameSession
  ): Either[RepositoryError, (GameState, GameSession)] =
    for
      restoredState <- gameRepo.redo(gameId)
      sessionToSave = nextSession(restoredState)
      _ <- sessionRepo.save(sessionToSave)
    yield (restoredState, sessionToSave)
