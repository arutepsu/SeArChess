package chess.application.port.repository

import chess.application.session.model.GameSession
import chess.application.session.model.SessionIds.{GameId, SessionId}

/** Outbound port for persisting and retrieving [[GameSession]] records.
 *
 *  This is an interface only.  Implementations (in-memory, JDBC, file-backed,
 *  etc.) belong in `chess.adapter.*` packages, not in the application layer.
 *
 *  The API surface is intentionally minimal for Phase 2:
 *  - [[save]] covers both create and update (upsert semantics).
 *  - [[load]] covers the primary access pattern (by session id).
 *  - [[loadByGameId]] covers the secondary pattern needed for game-oriented
 *    session lookup (e.g. restoring a session when only the game id is known).
 *
 *  All operations return `Either[RepositoryError, _]` so callers can handle
 *  missing records and storage failures without catching exceptions.
 */
trait SessionRepository:

  /** Persist a [[GameSession]], replacing any existing record with the same
   *  [[SessionId]].
   */
  def save(session: GameSession): Either[RepositoryError, Unit]

  /** Load a session by its unique [[SessionId]].
   *
   *  Returns [[RepositoryError.NotFound]] when no matching record exists.
   */
  def load(id: SessionId): Either[RepositoryError, GameSession]

  /** Load the session associated with a given [[GameId]].
   *
   *  Useful when the caller knows the game id but not the session id.
   *  Returns [[RepositoryError.NotFound]] when no session references that game.
   */
  def loadByGameId(id: GameId): Either[RepositoryError, GameSession]
