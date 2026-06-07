package chess.adapter.repository.postgres

import chess.application.bot.{BotChallengeColor, BotChallengeSession, BotChallengeStatus}
import chess.application.port.repository.{BotChallengeSessionRepository, RepositoryError}
import slick.jdbc.PostgresProfile.api.*

import java.sql.Timestamp
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

final class PostgresBotChallengeSessionRepository(
    db: Database,
    timeout: Duration = Duration.Inf,
    schema: Option[String] = None
) extends BotChallengeSessionRepository:

  private val tables = BotChallengeTables(schema)
  import tables.*

  override def save(session: BotChallengeSession): Either[RepositoryError, Unit] =
    run("Failed to save bot challenge session") {
      BotChallenges += rowFromSession(session)
    }

  override def update(session: BotChallengeSession): Either[RepositoryError, Unit] =
    run("Failed to update bot challenge session") {
      BotChallenges.filter(_.id === session.id).update(rowFromSession(session))
    }

  override def findById(id: UUID): Either[RepositoryError, Option[BotChallengeSession]] =
    try
      val rowOpt = Await.result(db.run(BotChallenges.filter(_.id === id).result.headOption), timeout)
      sequence(rowOpt.map(sessionFromRow))
    catch case NonFatal(e) => Left(RepositoryError.StorageFailure(safeMessage(e)))

  private def run(label: String)(action: DBIO[Int]): Either[RepositoryError, Unit] =
    try
      Await.result(db.run(action), timeout)
      Right(())
    catch case NonFatal(e) => Left(RepositoryError.StorageFailure(s"$label: ${safeMessage(e)}"))

  private def rowFromSession(session: BotChallengeSession): BotChallengeRow =
    BotChallengeRow(
      id = session.id,
      requestedByUserId = session.requestedByUserId,
      requestedByNicknameSnapshot = session.requestedByNicknameSnapshot,
      lichessUsername = session.lichessUsername,
      lichessUserId = session.lichessUserId,
      lichessChallengeId = session.lichessChallengeId,
      lichessChallengeUrl = session.lichessChallengeUrl,
      status = session.status.toString,
      clockLimitSeconds = session.clockLimitSeconds,
      clockIncrementSeconds = session.clockIncrementSeconds,
      color = session.color.toString,
      rated = session.rated,
      createdAt = Timestamp.from(session.createdAt),
      updatedAt = Timestamp.from(session.updatedAt),
      failureReason = session.failureReason
    )

  private def sessionFromRow(row: BotChallengeRow): Either[RepositoryError, BotChallengeSession] =
    for
      status <- parseStatus(row.status)
      color <- parseColor(row.color)
    yield BotChallengeSession(
      id = row.id,
      requestedByUserId = row.requestedByUserId,
      requestedByNicknameSnapshot = row.requestedByNicknameSnapshot,
      lichessUsername = row.lichessUsername,
      lichessUserId = row.lichessUserId,
      lichessChallengeId = row.lichessChallengeId,
      lichessChallengeUrl = row.lichessChallengeUrl,
      status = status,
      clockLimitSeconds = row.clockLimitSeconds,
      clockIncrementSeconds = row.clockIncrementSeconds,
      color = color,
      rated = row.rated,
      createdAt = row.createdAt.toInstant,
      updatedAt = row.updatedAt.toInstant,
      failureReason = row.failureReason
    )

  private def parseStatus(value: String): Either[RepositoryError, BotChallengeStatus] =
    BotChallengeStatus.values
      .find(_.toString == value)
      .toRight(RepositoryError.StorageFailure(s"Unknown bot challenge status in DB: $value"))

  private def parseColor(value: String): Either[RepositoryError, BotChallengeColor] =
    BotChallengeColor.values
      .find(_.toString == value)
      .toRight(RepositoryError.StorageFailure(s"Unknown bot challenge color in DB: $value"))

  private def sequence[A](value: Option[Either[RepositoryError, A]]): Either[RepositoryError, Option[A]] =
    value match
      case None => Right(None)
      case Some(Right(a)) => Right(Some(a))
      case Some(Left(e)) => Left(e)

  private def safeMessage(e: Throwable): String =
    Option(e.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

object PostgresBotChallengeSessionRepository:
  def apply(
      db: Database,
      timeout: Duration = Duration.Inf,
      schema: Option[String] = None
  ): PostgresBotChallengeSessionRepository =
    new PostgresBotChallengeSessionRepository(db, timeout, schema)

private final case class BotChallengeRow(
    id: UUID,
    requestedByUserId: UUID,
    requestedByNicknameSnapshot: String,
    lichessUsername: String,
    lichessUserId: Option[String],
    lichessChallengeId: Option[String],
    lichessChallengeUrl: Option[String],
    status: String,
    clockLimitSeconds: Int,
    clockIncrementSeconds: Int,
    color: String,
    rated: Boolean,
    createdAt: Timestamp,
    updatedAt: Timestamp,
    failureReason: Option[String]
)

private final class BotChallengeTables(schema: Option[String]):
  import slick.jdbc.PostgresProfile.api.*

  final class BotChallengeTable(tag: Tag)
      extends Table[BotChallengeRow](tag, schema, "bot_challenge_sessions"):
    def id = column[UUID]("id", O.PrimaryKey)
    def requestedByUserId = column[UUID]("requested_by_user_id")
    def requestedByNicknameSnapshot = column[String]("requested_by_nickname_snapshot")
    def lichessUsername = column[String]("lichess_username")
    def lichessUserId = column[Option[String]]("lichess_user_id")
    def lichessChallengeId = column[Option[String]]("lichess_challenge_id")
    def lichessChallengeUrl = column[Option[String]]("lichess_challenge_url")
    def status = column[String]("status")
    def clockLimitSeconds = column[Int]("clock_limit_seconds")
    def clockIncrementSeconds = column[Int]("clock_increment_seconds")
    def color = column[String]("color")
    def rated = column[Boolean]("rated")
    def createdAt = column[Timestamp]("created_at")
    def updatedAt = column[Timestamp]("updated_at")
    def failureReason = column[Option[String]]("failure_reason")

    def * =
      (
        id,
        requestedByUserId,
        requestedByNicknameSnapshot,
        lichessUsername,
        lichessUserId,
        lichessChallengeId,
        lichessChallengeUrl,
        status,
        clockLimitSeconds,
        clockIncrementSeconds,
        color,
        rated,
        createdAt,
        updatedAt,
        failureReason
      ).mapTo[BotChallengeRow]

  val BotChallenges = TableQuery[BotChallengeTable]
