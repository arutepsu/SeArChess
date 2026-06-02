package chess.adapter.repository.sqlite

import chess.application.external.BindingId
import chess.application.external.ExternalGameBinding
import chess.application.port.repository.{ExternalGameCommandStore, RepositoryError}
import chess.application.session.model.GameSession
import chess.domain.state.GameState
import java.time.Instant

/** SQLite-backed [[ExternalGameCommandStore]].
  *
  * All three write operations — session row, game-state row, and binding row — share a single JDBC
  * transaction via [[SqliteDataSource.withTransaction]]. Either all three land or none do.
  *
  * ===Design notes===
  *   - Session upsert reuses [[SqliteSessionRepository.upsertSession]] (package-private).
  *   - Game-state upsert reuses [[SqliteGameRepository.upsertState]] (package-private).
  *   - Binding insert/update uses [[SqliteExternalGameBindingRepository]] helpers (package-private).
  * This avoids duplicating SQL while keeping the transaction boundary in this class.
  */
class SqliteExternalGameCommandStore(
    ds: SqliteDataSource,
    sessionRepo: SqliteSessionRepository,
    gameRepo: SqliteGameRepository,
    bindingRepo: SqliteExternalGameBindingRepository
) extends ExternalGameCommandStore:

  override def createWithBinding(
      session: GameSession,
      state: GameState,
      binding: ExternalGameBinding
  ): Either[RepositoryError, Unit] =
    ds.withTransaction { conn =>
      try
        sessionRepo.upsertSession(conn, session)
        gameRepo.upsertState(conn, session.gameId, state)
        bindingRepo.insertBinding(conn, binding)
        Right(())
      catch
        case e: java.sql.SQLException if isUniqueViolation(e) =>
          Left(RepositoryError.Conflict(
            s"Binding already exists for (${binding.platform}, ${binding.externalGameId})"
          ))
        case e: java.sql.SQLException =>
          Left(RepositoryError.StorageFailure(e.getMessage))
    }

  override def saveMoveWithPlyAdvance(
      session: GameSession,
      state: GameState,
      bindingId: BindingId,
      newPly: Int,
      now: Instant
  ): Either[RepositoryError, Unit] =
    ds.withTransaction { conn =>
      try
        sessionRepo.upsertSession(conn, session)
        gameRepo.upsertState(conn, session.gameId, state)
        val rowsUpdated = bindingRepo.advancePlyInTx(conn, bindingId, newPly, now)
        if rowsUpdated == 0 then
          Left(RepositoryError.Conflict(
            s"Ply advance rejected for binding ${bindingId.value}: binding not Active or ply $newPly is not greater than current"
          ))
        else Right(())
      catch
        case e: java.sql.SQLException =>
          Left(RepositoryError.StorageFailure(e.getMessage))
    }

  override def saveFinishedWithBinding(
      session: GameSession,
      state: GameState,
      bindingId: BindingId,
      now: Instant
  ): Either[RepositoryError, Unit] =
    ds.withTransaction { conn =>
      try
        sessionRepo.upsertSession(conn, session)
        gameRepo.upsertState(conn, session.gameId, state)
        val rowsUpdated = bindingRepo.markFinishedInTx(conn, bindingId, now)
        if rowsUpdated == 0 then
          Left(RepositoryError.Conflict(
            s"Cannot finish binding ${bindingId.value}: not found or not Active"
          ))
        else Right(())
      catch
        case e: java.sql.SQLException =>
          Left(RepositoryError.StorageFailure(e.getMessage))
    }

  private def isUniqueViolation(e: java.sql.SQLException): Boolean =
    e.getErrorCode == 19 || Option(e.getMessage).exists(_.contains("UNIQUE constraint failed"))
