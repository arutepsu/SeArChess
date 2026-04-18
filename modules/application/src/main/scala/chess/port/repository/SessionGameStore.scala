package chess.application.port.repository

import chess.application.session.model.GameSession
import chess.domain.state.GameState

/** Combined write port for session metadata and game state.
 *
 *  Exposes a single [[save]] operation that persists both a [[GameSession]] and
 *  its associated [[GameState]] as one logical unit.  Callers that use this port
 *  instead of separate [[SessionRepository]] and [[GameRepository]] writes
 *  eliminate the partial-failure window that exists when the two writes are
 *  independent: either both succeed or the caller receives an error.
 *
 *  === Data ownership for the future extracted service ===
 *  This port defines the authoritative write scope of the game-session command
 *  capability (see [[chess.application.session.service.GameSessionCommands]]).
 *  The future extracted game-session command service would own:
 *  - the session write model ([[GameSession]] records)
 *  - the game-state write model ([[chess.domain.state.GameState]] records associated
 *    with session-aware play)
 *
 *  No other service or adapter should perform direct authoritative writes to
 *  either of these records outside of this port.
 *
 *  === Read paths ===
 *  This port is write-only.  Reads are still performed through
 *  [[SessionRepository]] (for session metadata) and [[GameRepository]] (for
 *  game state) so that existing read-side adapters require no changes.
 *
 *  === Implementations ===
 *  - [[chess.adapter.repository.InMemorySessionGameStore]] — sequential in-memory
 *    writes; used in tests and the desktop composition root.
 *  - A future JDBC implementation would wrap both writes in a single transaction.
 */
trait SessionGameStore:

  /** Persist the updated session and its new game state as one logical unit.
   *
   *  Implementations backed by a relational database should wrap both writes
   *  in a single transaction so that a partial write is never visible.
   *
   *  @param session the updated session metadata to persist
   *  @param state   the new game state associated with the session's game id
   *  @return [[Right]] on success; [[Left]] with a [[RepositoryError]] if either
   *          write fails
   */
  def save(session: GameSession, state: GameState): Either[RepositoryError, Unit]

  /** Undo the last move atomically, computing the new session before persisting it.
   *
   *  @param gameId Game ID to revert
   *  @param nextSession callback mapping the restored domain state to an updated session
   */
  def undo(gameId: chess.application.session.model.SessionIds.GameId, nextSession: GameState => GameSession): Either[RepositoryError, (GameState, GameSession)]

  /** Redo the undone move atomically, computing the new session before persisting it.
   */
  def redo(gameId: chess.application.session.model.SessionIds.GameId, nextSession: GameState => GameSession): Either[RepositoryError, (GameState, GameSession)]
