package chess.application.port.repository

import chess.application.session.model.GameSession
import chess.application.session.model.SessionIds.{GameId, SessionId}

/** Outbound port for persisting and retrieving [[GameSession]] records.
  *
  * This is an interface only. Implementations (in-memory, JDBC, file-backed, etc.) belong in
  * `chess.adapter.*` packages, not in the application layer.
  *
  * The API surface is intentionally minimal for Phase 2:
  *   - [[save]] covers both create and update (upsert semantics).
  *   - [[load]] covers the primary access pattern (by session id).
  *   - [[loadByGameId]] covers the secondary pattern needed for game-oriented session lookup (e.g.
  *     restoring a session when only the game id is known).
  *
  * ===Transactional cancel===
  * [[saveCancelWithOutbox]] extends [[save]] with an atomically co-written outbox payload. The
  * default delegates to [[save]] (correct for in-memory and no-outbox modes). The SQLite
  * implementation wraps both writes in one JDBC transaction.
  *
  * All operations return `Either[RepositoryError, _]` so callers can handle missing records and
  * storage failures without catching exceptions.
  */
trait SessionRepository:

  /** Persist a [[GameSession]], replacing any existing record with the same [[SessionId]].
    */
  def save(session: GameSession): Either[RepositoryError, Unit]

  /** Load a session by its unique [[SessionId]].
    *
    * Returns [[RepositoryError.NotFound]] when no matching record exists.
    */
  def load(id: SessionId): Either[RepositoryError, GameSession]

  /** Load the session associated with a given [[GameId]].
    *
    * Useful when the caller knows the game id but not the session id. Returns
    * [[RepositoryError.NotFound]] when no session references that game.
    */
  def loadByGameId(id: GameId): Either[RepositoryError, GameSession]

  /** Return all sessions that have not yet reached a terminal lifecycle.
    *
    * Returns an empty list (not [[RepositoryError.NotFound]]) when no active sessions exist. Order
    * is implementation-defined.
    */
  def listActive(): Either[RepositoryError, List[GameSession]]

  /** Persist a cancelled [[GameSession]] and its outbox payload in one atomic operation.
    *
    * The default implementation delegates to [[save]] and discards `outboxPayload`. This is correct
    * when no durable outbox is configured (in-memory persistence, or no-op serializer).
    *
    * SQLite implementations override this to wrap the session upsert and the outbox row insert in a
    * single JDBC transaction. If either write fails the other is also rolled back.
    *
    * @param session
    *   the session with lifecycle advanced to Cancelled
    * @param outboxPayload
    *   serialised JSON for the SessionCancelled event, or [[None]] when no outbox is configured
    */
  def saveCancelWithOutbox(
      session: GameSession,
      outboxPayload: Option[String]
  ): Either[RepositoryError, Unit] =
    save(session)
