package chess.application.port.repository

import chess.application.external.{BindingId, ExternalGameBinding}
import chess.application.session.model.GameSession
import chess.domain.state.GameState
import java.time.Instant

/** Atomic write boundary for external-game move application.
  *
  * Each method wraps two or three table writes in a single transaction so that state is never
  * partially visible:
  *   - session row
  *   - game_state row
  *   - external_game_bindings row (ply advance or status change)
  *
  * ===Why a separate port from [[SessionGameStore]]===
  * The external-game write path bypasses [[chess.application.session.service.SessionGameCommandService]]
  * because it must advance `lastProcessedPly` in the same transaction as the session+state write.
  * [[SessionGameStore]] has no knowledge of binding records. Rather than adding external-game
  * awareness to the existing store, a dedicated port keeps the write scopes independent and
  * testable in isolation.
  *
  * ===In-memory implementations===
  * The default implementations perform sequential writes without true ACID guarantees. This is
  * correct for tests (no concurrent writers, no crash recovery needed) and for single-JVM embedded
  * deployments.
  */
trait ExternalGameCommandStore:

  /** Atomically persist a new session, its initial game state, and a new external binding.
    *
    * Returns [[RepositoryError.Conflict]] if a binding already exists for the same
    * `(platform, externalGameId)`.
    */
  def createWithBinding(
      session: GameSession,
      state: GameState,
      binding: ExternalGameBinding
  ): Either[RepositoryError, Unit]

  /** Atomically persist an updated session, updated game state, and advance the binding's
    * `lastProcessedPly` to `newPly`.
    *
    * Returns [[RepositoryError.Conflict]] if the binding is not in [[chess.application.external.BindingStatus.Active]]
    * status or if `newPly` does not strictly exceed the current value.
    */
  def saveMoveWithPlyAdvance(
      session: GameSession,
      state: GameState,
      bindingId: BindingId,
      newPly: Int,
      now: Instant
  ): Either[RepositoryError, Unit]

  /** Atomically persist a finished session, the final game state, and mark the binding as
    * [[chess.application.external.BindingStatus.Finished]].
    */
  def saveFinishedWithBinding(
      session: GameSession,
      state: GameState,
      bindingId: BindingId,
      now: Instant
  ): Either[RepositoryError, Unit]
