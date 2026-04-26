package chess.adapter.repository.postgres

import chess.application.migration.{
  SessionMigrationBatch,
  SessionMigrationCursor,
  SessionMigrationReader
}
import chess.application.port.repository.RepositoryError
import chess.application.session.model.GameSession
import slick.jdbc.PostgresProfile.api.*

import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

class PostgresSessionMigrationReader(
    db: Database,
    timeout: Duration = Duration.Inf
) extends SessionMigrationReader:

  import PostgresSessionRepository.Sessions

  private given ExecutionContext = ExecutionContext.global

  override def readBatch(
      cursor: Option[SessionMigrationCursor],
      batchSize: Int
  ): Either[RepositoryError, SessionMigrationBatch] =
    run {
      decodeCursor(cursor) match
        case Left(error) => DBIO.successful(Left(error))
        case Right(lastSeenSessionId) =>
          val baseQuery =
            Sessions
              .sortBy(_.sessionId.asc)
              .filterOpt(lastSeenSessionId)((table, lastSeen) => table.sessionId > lastSeen)
              .take(batchSize + 1)

          baseQuery.result.map(rows => buildBatch(rows.toList, batchSize, cursor))
    }

  private def buildBatch(
      rows: List[PostgresSessionRow],
      batchSize: Int,
      requestedCursor: Option[SessionMigrationCursor]
  ): Either[RepositoryError, SessionMigrationBatch] =
    if rows.isEmpty && requestedCursor.nonEmpty then
      Left(
        RepositoryError.StorageFailure(
          "Session migration cursor does not reference any remaining Postgres sessions"
        )
      )
    else
      val pageRows = rows.take(batchSize)
      for
        sessions <- sequence(pageRows.map(PostgresSessionMapper.toSession))
      yield
        val nextCursor =
          rows.lift(batchSize - 1).filter(_ => rows.size > batchSize).map(row =>
            SessionMigrationCursor(row.sessionId.toString)
          )
        SessionMigrationBatch(sessions, nextCursor)

  private def decodeCursor(
      cursor: Option[SessionMigrationCursor]
  ): Either[RepositoryError, Option[UUID]] =
    cursor match
      case None => Right(None)
      case Some(value) =>
        scala.util.Try(UUID.fromString(value.value)).toEither.left.map(_ =>
          RepositoryError.StorageFailure(
            s"Invalid Postgres session migration cursor: ${value.value}"
          )
        ).map(Some.apply)

  private def run[A](action: DBIO[Either[RepositoryError, A]]): Either[RepositoryError, A] =
    try Await.result(db.run(action), timeout)
    catch case NonFatal(error) =>
      Left(
        RepositoryError.StorageFailure(
          Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
        )
      )

  private def sequence[A](values: List[Either[RepositoryError, A]]): Either[RepositoryError, List[A]] =
    values.foldRight(Right(Nil): Either[RepositoryError, List[A]]) { (next, acc) =>
      for
        value <- next
        rest <- acc
      yield value :: rest
    }

object PostgresSessionMigrationReader:
  def apply(
      db: Database,
      timeout: Duration = Duration.Inf
  ): PostgresSessionMigrationReader =
    new PostgresSessionMigrationReader(db, timeout)
