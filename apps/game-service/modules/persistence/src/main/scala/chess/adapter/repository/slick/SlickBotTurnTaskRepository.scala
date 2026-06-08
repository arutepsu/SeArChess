package chess.adapter.repository.slick

import _root_.slick.dbio.DBIO
import _root_.slick.jdbc.JdbcProfile
import chess.application.bot.{BotTurnLeaseSweepResult, BotTurnStatus, BotTurnTask, BotTurnTaskRepository}
import chess.application.port.repository.RepositoryError
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.Color
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

/** Generic Slick implementation of [[BotTurnTaskRepository]].
  *
  * Postgres support is provided by
  * [[chess.adapter.repository.postgres.PostgresBotTurnTaskRepository]], which extends this class
  * with the Postgres profile and schema support.
  *
  * [[leaseNextPending]] uses a raw SQL `SELECT ... FOR UPDATE SKIP LOCKED` query because Slick's
  * lifted embedding does not support that locking clause. UUIDs are passed as plain VARCHAR strings
  * to avoid requiring GetResult/SetParameter implicits for UUID.
  */
class SlickBotTurnTaskRepository(
    val profile: JdbcProfile
)(
    db: profile.backend.Database,
    val tables: SlickTables,
    timeout: Duration = Duration.Inf,
    schema: Option[String] = None
) extends BotTurnTaskRepository:

  import profile.api.*
  import tables.*

  private given ExecutionContext = ExecutionContext.global

  private val schemaPrefix: String =
    schema.map(_.trim).filter(_.nonEmpty).map(s => s""""$s".""").getOrElse("")

  override def createPending(task: BotTurnTask): Either[RepositoryError, Unit] =
    run("Duplicate bot turn task id") {
      (BotTurnTasks += toRow(task)).map(_ => Right(()))
    }

  override def leaseNextPending(
      actorId: String,
      now: Instant,
      leaseSeconds: Int,
      limit: Int
  ): Either[RepositoryError, List[BotTurnTask]] =
    val leaseUntil  = now.plusSeconds(leaseSeconds.toLong)
    val leaseTs     = Timestamp.from(leaseUntil)
    val nowTs       = Timestamp.from(now)
    val tableName   = s"${schemaPrefix}bot_turn_tasks"

    val action: DBIO[Either[RepositoryError, List[BotTurnTask]]] =
      for
        idStrings <- sql"""
          SELECT id::text
          FROM #$tableName
          WHERE status = 'Pending' AND bot_actor_id = $actorId
          ORDER BY created_at
          LIMIT $limit
          FOR UPDATE SKIP LOCKED
        """.as[String]
        _ <- DBIO.seq(idStrings.map { idStr =>
          sqlu"""
            UPDATE #$tableName
            SET status = 'Leased',
                lease_until = $leaseTs,
                attempt_count = attempt_count + 1,
                updated_at = $nowTs
            WHERE id = $idStr::uuid
          """
        }*)
        rows <- BotTurnTasks
          .filter(_.id inSet idStrings.map(UUID.fromString).toSet)
          .result
      yield SlickRepositorySupport.sequence(rows.toList.map(fromRow))

    run(action.transactionally)

  override def markCompleted(taskId: UUID, now: Instant): Either[RepositoryError, Unit] =
    run {
      BotTurnTasks
        .filter(_.id === taskId)
        .map(t => (t.status, t.updatedAt))
        .update(("Completed", Timestamp.from(now)))
        .map {
          case 0 => Left(RepositoryError.NotFound(taskId.toString))
          case _ => Right(())
        }
    }

  override def markFailed(taskId: UUID, error: String, now: Instant): Either[RepositoryError, Unit] =
    run {
      BotTurnTasks
        .filter(_.id === taskId)
        .map(t => (t.status, t.lastError, t.updatedAt))
        .update(("Failed", Some(error), Timestamp.from(now)))
        .map {
          case 0 => Left(RepositoryError.NotFound(taskId.toString))
          case _ => Right(())
        }
    }

  override def releaseExpiredLeases(
      now: Instant,
      maxAttempts: Int
  ): Either[RepositoryError, BotTurnLeaseSweepResult] =
    val nowTs = Timestamp.from(now)
    run {
      val tableName = s"${schemaPrefix}bot_turn_tasks"
      for
        failed <- sqlu"""
          UPDATE #$tableName
          SET status = 'Failed',
              last_error = 'Bot turn lease expired after max attempts',
              updated_at = $nowTs
          WHERE status = 'Leased'
            AND lease_until < $nowTs
            AND attempt_count >= $maxAttempts
        """
        released <- sqlu"""
          UPDATE #$tableName
          SET status = 'Pending', lease_until = NULL, updated_at = $nowTs
          WHERE status = 'Leased'
            AND lease_until < $nowTs
            AND attempt_count < $maxAttempts
        """
      yield Right(BotTurnLeaseSweepResult(released = released, failed = failed))
    }

  override def findById(taskId: UUID): Either[RepositoryError, Option[BotTurnTask]] =
    run {
      BotTurnTasks
        .filter(_.id === taskId)
        .result
        .headOption
        .map {
          case None      => Right(None)
          case Some(row) => fromRow(row).map(Some.apply)
        }
    }

  override def hasPendingOrLeased(
      sessionId: SessionId,
      gameId: GameId
  ): Either[RepositoryError, Boolean] =
    run {
      BotTurnTasks
        .filter(t =>
          t.sessionId === sessionId.value &&
            t.gameId === gameId.value &&
            (t.status === "Pending" || t.status === "Leased")
        )
        .exists
        .result
        .map(found => Right(found))
    }

  // ── row conversions ───────────────────────────────────────────────────────

  private[repository] def toRow(task: BotTurnTask): SlickBotTurnTaskRow =
    SlickBotTurnTaskRow(
      id           = task.id,
      sessionId    = task.sessionId.value,
      gameId       = task.gameId.value,
      botActorId   = task.botActorId,
      sideToMove   = task.sideToMove.toString,
      status       = task.status.toString,
      leaseUntil   = task.leaseUntil.map(Timestamp.from),
      attemptCount = task.attemptCount,
      lastError    = task.lastError,
      createdAt    = Timestamp.from(task.createdAt),
      updatedAt    = Timestamp.from(task.updatedAt)
    )

  private[repository] def fromRow(row: SlickBotTurnTaskRow): Either[RepositoryError, BotTurnTask] =
    for
      color  <- parseColor(row.sideToMove)
      status <- parseStatus(row.status)
    yield BotTurnTask(
      id           = row.id,
      sessionId    = SessionId(row.sessionId),
      gameId       = GameId(row.gameId),
      botActorId   = row.botActorId,
      sideToMove   = color,
      status       = status,
      leaseUntil   = row.leaseUntil.map(_.toInstant),
      attemptCount = row.attemptCount,
      lastError    = row.lastError,
      createdAt    = row.createdAt.toInstant,
      updatedAt    = row.updatedAt.toInstant
    )

  private def parseColor(value: String): Either[RepositoryError, Color] =
    value match
      case "White" => Right(Color.White)
      case "Black" => Right(Color.Black)
      case other   => Left(RepositoryError.StorageFailure(s"Unknown color in DB: $other"))

  private def parseStatus(value: String): Either[RepositoryError, BotTurnStatus] =
    value match
      case "Pending"   => Right(BotTurnStatus.Pending)
      case "Leased"    => Right(BotTurnStatus.Leased)
      case "Completed" => Right(BotTurnStatus.Completed)
      case "Failed"    => Right(BotTurnStatus.Failed)
      case other       => Left(RepositoryError.StorageFailure(s"Unknown bot turn status in DB: $other"))

  // ── run helpers ────────────────────────────────────────────────────────────

  private def run[A](action: DBIO[Either[RepositoryError, A]]): Either[RepositoryError, A] =
    SlickRepositorySupport.run(profile)(db, timeout)(action)

  private def run[A](
      conflictMsg: String
  )(
      action: DBIO[Either[RepositoryError, A]]
  ): Either[RepositoryError, A] =
    SlickRepositorySupport.run(profile)(db, timeout, Some(conflictMsg))(action)
