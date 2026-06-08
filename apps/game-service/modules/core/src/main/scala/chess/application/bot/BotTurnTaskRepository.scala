package chess.application.bot

import chess.application.port.repository.RepositoryError
import chess.application.session.model.SessionIds.{GameId, SessionId}
import java.time.Instant
import java.util.UUID

/** Outbound port for persisting and querying [[BotTurnTask]] records.
  *
  * Implementations belong in the adapter layer. The application layer depends only on this trait.
  *
  * All operations return `Either[RepositoryError, _]` so callers can handle failures without
  * catching exceptions.
  */
trait BotTurnTaskRepository:

  /** Persist a new task in [[BotTurnStatus.Pending]] status. */
  def createPending(task: BotTurnTask): Either[RepositoryError, Unit]

  /** Atomically lease up to `limit` pending tasks for `actorId`.
    *
    * Selects rows with `status='Pending'` and `bot_actor_id=actorId`, locks them with
    * `SELECT FOR UPDATE SKIP LOCKED`, then transitions each to `status='Leased'` with
    * `lease_until=now+leaseSeconds` and increments `attempt_count`.
    *
    * Returns the updated (leased) tasks.
    */
  def leaseNextPending(
      actorId: String,
      now: Instant,
      leaseSeconds: Int,
      limit: Int
  ): Either[RepositoryError, List[BotTurnTask]]

  /** Transition a task to [[BotTurnStatus.Completed]]. */
  def markCompleted(taskId: UUID, now: Instant): Either[RepositoryError, Unit]

  /** Transition a task to [[BotTurnStatus.Failed]] with an error message. */
  def markFailed(taskId: UUID, error: String, now: Instant): Either[RepositoryError, Unit]

  /** Re-queue or fail expired leases whose `lease_until < now`.
    *
    * Leased tasks below `maxAttempts` return to [[BotTurnStatus.Pending]]. Leased tasks whose
    * `attempt_count >= maxAttempts` transition to [[BotTurnStatus.Failed]].
    */
  def releaseExpiredLeases(now: Instant, maxAttempts: Int): Either[RepositoryError, BotTurnLeaseSweepResult]

  /** Load a single task by its primary key. */
  def findById(taskId: UUID): Either[RepositoryError, Option[BotTurnTask]]

  /** Return `true` when a Pending or Leased task already exists for the given session/game pair.
    *
    * Used to avoid creating duplicate tasks (idempotency guard in [[SessionGameCommandService]]).
    */
  def hasPendingOrLeased(
      sessionId: SessionId,
      gameId: GameId
  ): Either[RepositoryError, Boolean]

final case class BotTurnLeaseSweepResult(released: Int, failed: Int)
