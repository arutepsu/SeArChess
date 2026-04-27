package chess.application.port.repository

import chess.application.session.model.GameSession
import chess.domain.state.GameState

/** Combined write port for session metadata and game state.
  *
  * Exposes a single [[save]] operation that persists both a [[GameSession]] and its associated
  * [[GameState]] as one logical unit. Callers that use this port instead of separate
  * [[SessionRepository]] and [[GameRepository]] writes eliminate the partial-failure window that
  * exists when the two writes are independent: either both succeed or the caller receives an error.
  *
  * ===Transactional outbox write===
  * [[saveTerminal]] extends the atomicity guarantee to include pre-serialised outbox payloads. When
  * SQLite is the backing store, all three writes — session row, game-state row, and outbox rows —
  * land in the same JDBC transaction. If any write fails the entire transaction is rolled back.
  *
  * In-memory and other non-SQLite implementations inherit the default implementation of
  * [[saveTerminal]], which delegates to [[save]] and ignores the payload list (no durable outbox
  * exists in those modes).
  *
  * ===Data ownership for the future extracted service===
  * This port defines the authoritative write scope of the game-session command capability (see
  * [[chess.application.session.service.GameSessionCommands]]).
  */
trait SessionGameStore:

  /** Persist the updated session and its new game state as one logical unit.
    *
    * Implementations backed by a relational database should wrap both writes in a single
    * transaction so that a partial write is never visible.
    *
    * @param session
    *   the updated session metadata to persist
    * @param state
    *   the new game state associated with the session's game id
    * @return
    *   [[Right]] on success; [[Left]] with a [[RepositoryError]] if either write fails
    */
  def save(session: GameSession, state: GameState): Either[RepositoryError, Unit]

  /** Persist session + game state + durable outbox payloads in one atomic operation.
    *
    * The default implementation delegates to [[save]] and discards `outboxPayloads`. This is
    * correct for in-memory persistence and any mode where no durable outbox is configured: the
    * caller supplies a no-op serializer so `outboxPayloads` is always empty, and [[save]] suffices.
    *
    * SQLite implementations override this to include all three writes in a single JDBC transaction:
    * if the outbox insert fails the session and game state writes are also rolled back, closing the
    * consistency gap.
    *
    * @param session
    *   the updated session metadata
    * @param state
    *   the new game state
    * @param outboxPayloads
    *   pre-serialised JSON strings to insert into the outbox; empty when no durable outbox is
    *   configured
    */
  def saveTerminal(
      session: GameSession,
      state: GameState,
      outboxPayloads: List[String]
  ): Either[RepositoryError, Unit] =
    save(session, state)
