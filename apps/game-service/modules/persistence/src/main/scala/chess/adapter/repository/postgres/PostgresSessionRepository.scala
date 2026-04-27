package chess.adapter.repository.postgres

import chess.application.port.repository.{RepositoryError, SessionRepository}
import chess.application.session.model.GameSession
import chess.application.session.model.SessionIds.{GameId, SessionId}
import slick.jdbc.PostgresProfile.api.*

import java.sql.SQLException
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

class PostgresSessionRepository(
    db: Database,
    timeout: Duration = Duration.Inf
) extends SessionRepository:

  import PostgresSessionRepository.*

  private given ExecutionContext = ExecutionContext.global

  override def save(session: GameSession): Either[RepositoryError, Unit] =
    run {
      val row = PostgresSessionMapper.toRow(session)
      val owner =
        Sessions
          .filter(_.gameId === session.gameId.value)
          .map(_.sessionId)
          .result
          .headOption

      owner
        .flatMap {
          case Some(existingSessionId) if existingSessionId != session.sessionId.value =>
            DBIO.successful(
              Left(
                RepositoryError.Conflict(
                  s"GameId ${session.gameId.value} is already owned by session $existingSessionId"
                )
              )
            )
          case _ =>
            Sessions.insertOrUpdate(row).map(_ => Right(()))
        }
        .transactionally
    }

  override def load(id: SessionId): Either[RepositoryError, GameSession] =
    run {
      Sessions
        .filter(_.sessionId === id.value)
        .result
        .headOption
        .map:
          case Some(row) => PostgresSessionMapper.toSession(row)
          case None      => Left(RepositoryError.NotFound(id.value.toString))
    }

  override def loadByGameId(id: GameId): Either[RepositoryError, GameSession] =
    run {
      Sessions
        .filter(_.gameId === id.value)
        .result
        .headOption
        .map:
          case Some(row) => PostgresSessionMapper.toSession(row)
          case None      => Left(RepositoryError.NotFound(id.value.toString))
    }

  override def listActive(): Either[RepositoryError, List[GameSession]] =
    run {
      Sessions
        .filterNot(_.lifecycle inSet Set("Finished", "Cancelled"))
        .result
        .map(rows => sequence(rows.toList.map(PostgresSessionMapper.toSession)))
    }

  private def run[A](action: DBIO[Either[RepositoryError, A]]): Either[RepositoryError, A] =
    try Await.result(db.run(action), timeout)
    catch case NonFatal(e) => Left(toRepositoryError(e))

  private def toRepositoryError(error: Throwable): RepositoryError =
    if isUniqueViolation(error) then
      RepositoryError.Conflict("GameId is already owned by another session")
    else
      RepositoryError.StorageFailure(
        Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
      )

  private def isUniqueViolation(error: Throwable): Boolean =
    error match
      case sql: SQLException if sql.getSQLState == "23505" => true
      case _ =>
        Option(error.getCause).exists(isUniqueViolation)

  private def sequence[A](values: List[Either[RepositoryError, A]]): Either[RepositoryError, List[A]] =
    values.foldRight(Right(Nil): Either[RepositoryError, List[A]]) { (next, acc) =>
      for
        value <- next
        rest <- acc
      yield value :: rest
    }

object PostgresSessionRepository:
  private[postgres] val Sessions = TableQuery[PostgresSessionTable]
