package chess.adapter.repository.slick

import _root_.slick.dbio.DBIO
import _root_.slick.jdbc.JdbcProfile
import chess.application.migration.{
  SessionMigrationBatch,
  SessionMigrationCursor,
  SessionMigrationReader
}
import chess.application.port.repository.RepositoryError

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

class SlickSessionMigrationReader(
    val profile: JdbcProfile
)(
    db: profile.backend.Database,
    val tables: SlickTables,
    timeout: Duration = Duration.Inf,
    cursorStoreName: String = "Slick"
) extends SessionMigrationReader:

  import profile.api.*
  import tables.*

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
      rows: List[SlickSessionRow],
      batchSize: Int,
      requestedCursor: Option[SessionMigrationCursor]
  ): Either[RepositoryError, SessionMigrationBatch] =
    if rows.isEmpty && requestedCursor.nonEmpty then
      Left(
        RepositoryError.StorageFailure(
          s"Session migration cursor does not reference any remaining $cursorStoreName sessions"
        )
      )
    else
      val pageRows = rows.take(batchSize)
      for
        sessions <- SlickRepositorySupport.sequence(pageRows.map(SlickSessionMapper.toSession(tables)))
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
            s"Invalid $cursorStoreName session migration cursor: ${value.value}"
          )
        ).map(Some.apply)

  private def run[A](action: DBIO[Either[RepositoryError, A]]): Either[RepositoryError, A] =
    SlickRepositorySupport.run(profile)(db, timeout)(action)
