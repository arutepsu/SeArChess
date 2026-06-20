package chess.application.bot

import chess.application.port.repository.RepositoryError
import chess.application.session.model.SessionIds.{GameId, SessionId}
import java.time.Instant
import java.util.UUID
import scala.collection.mutable

/** Thread-safe in-memory implementation of [[BotTurnTaskRepository]].
  *
  * Intended for unit tests only. State is lost on JVM exit.
  */
class InMemoryBotTurnTaskRepository extends BotTurnTaskRepository:

  private val store = mutable.HashMap.empty[UUID, BotTurnTask]

  override def createPending(task: BotTurnTask): Either[RepositoryError, Unit] =
    synchronized { store.put(task.id, task); Right(()) }

  override def leaseNextPending(
      actorId: String,
      now: Instant,
      leaseSeconds: Int,
      limit: Int
  ): Either[RepositoryError, List[BotTurnTask]] =
    synchronized {
      val pending = store.values
        .filter(t => t.status == BotTurnStatus.Pending && t.botActorId == actorId)
        .take(limit)
        .toList
      val leased = pending.map { task =>
        val updated = task.copy(
          status = BotTurnStatus.Leased,
          leaseUntil = Some(now.plusSeconds(leaseSeconds.toLong)),
          attemptCount = task.attemptCount + 1,
          updatedAt = now
        )
        store.put(updated.id, updated)
        updated
      }
      Right(leased)
    }

  override def markCompleted(taskId: UUID, now: Instant): Either[RepositoryError, Unit] =
    synchronized {
      store.get(taskId) match
        case None => Left(RepositoryError.NotFound(taskId.toString))
        case Some(task) =>
          store.put(taskId, task.copy(status = BotTurnStatus.Completed, updatedAt = now))
          Right(())
    }

  override def markFailed(taskId: UUID, error: String, now: Instant): Either[RepositoryError, Unit] =
    synchronized {
      store.get(taskId) match
        case None => Left(RepositoryError.NotFound(taskId.toString))
        case Some(task) =>
          store.put(
            taskId,
            task.copy(status = BotTurnStatus.Failed, lastError = Some(error), updatedAt = now)
          )
          Right(())
    }

  override def releaseExpiredLeases(now: Instant, maxAttempts: Int): Either[RepositoryError, BotTurnLeaseSweepResult] =
    synchronized {
      val expired = store.values.filter { t =>
        t.status == BotTurnStatus.Leased &&
          t.leaseUntil.exists(_.isBefore(now))
      }.toList
      val (failed, releasable) = expired.partition(_.attemptCount >= maxAttempts)
      failed.foreach { task =>
        store.put(
          task.id,
          task.copy(
            status = BotTurnStatus.Failed,
            lastError = Some(s"Bot turn lease expired after ${task.attemptCount} attempts"),
            updatedAt = now
          )
        )
      }
      releasable.foreach { task =>
        store.put(task.id, task.copy(status = BotTurnStatus.Pending, leaseUntil = None, updatedAt = now))
      }
      Right(BotTurnLeaseSweepResult(released = releasable.size, failed = failed.size))
    }

  override def findById(taskId: UUID): Either[RepositoryError, Option[BotTurnTask]] =
    synchronized { Right(store.get(taskId)) }

  override def hasPendingOrLeased(
      sessionId: SessionId,
      gameId: GameId
  ): Either[RepositoryError, Boolean] =
    synchronized {
      val found = store.values.exists { t =>
        t.sessionId == sessionId &&
          t.gameId == gameId &&
          (t.status == BotTurnStatus.Pending || t.status == BotTurnStatus.Leased)
      }
      Right(found)
    }
