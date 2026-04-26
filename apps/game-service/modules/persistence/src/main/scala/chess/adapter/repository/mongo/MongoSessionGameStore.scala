package chess.adapter.repository.mongo

import chess.application.port.repository.{RepositoryError, SessionGameStore}
import chess.application.session.model.GameSession
import chess.domain.state.GameState

/**
 * Mongo-backed [[SessionGameStore]] with logical success semantics over two repository writes.
 *
 * A successful `Right(())` means both the session write and the game-state write completed.
 * This implementation does not currently provide rollback-atomic behavior comparable to the
 * Postgres implementation, which uses a real database transaction.
 *
 * The write sequence is:
 * 1. save the [[GameSession]] through the Mongo session repository
 * 2. save the [[GameState]] through the Mongo game repository
 *
 * If the second write fails after the first one succeeds, the target may contain a partial
 * aggregate and operational reconciliation may be required. That limitation is intentional and
 * explicit: this class preserves the `SessionGameStore` boundary used by migration and runtime
 * code, but it does not claim stronger consistency guarantees than the current Mongo deployment
 * path can provide.
 *
 * Future production hardening should use Mongo transactions where the deployment topology and
 * driver configuration support them.
 */
class MongoSessionGameStore(
    sessionRepository: MongoSessionRepository,
    gameRepository: MongoGameRepository
) extends SessionGameStore:

  override def save(session: GameSession, state: GameState): Either[RepositoryError, Unit] =
    for
      _ <- sessionRepository.save(session)
      _ <- gameRepository.save(session.gameId, state)
    yield ()
