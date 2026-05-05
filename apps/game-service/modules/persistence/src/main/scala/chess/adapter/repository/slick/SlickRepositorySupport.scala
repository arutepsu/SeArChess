package chess.adapter.repository.slick

import _root_.slick.dbio.DBIO
import _root_.slick.jdbc.JdbcProfile
import chess.application.port.repository.RepositoryError

import java.sql.SQLException
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

object SlickRepositorySupport:
  def run[A](
      profile: JdbcProfile
  )(
      db: profile.backend.Database,
      timeout: Duration
  )(
      action: DBIO[Either[RepositoryError, A]]
  ): Either[RepositoryError, A] =
    run(profile)(db, timeout, None)(action)

  def run[A](
      profile: JdbcProfile
  )(
      db: profile.backend.Database,
      timeout: Duration,
      uniqueConflictMessage: Option[String]
  )(
      action: DBIO[Either[RepositoryError, A]]
  ): Either[RepositoryError, A] =
    try Await.result(db.run(action), timeout)
    catch case NonFatal(error) => Left(toRepositoryError(error, uniqueConflictMessage))

  def storageFailure(message: String): RepositoryError.StorageFailure =
    RepositoryError.StorageFailure(message)

  def storageFailure(error: Throwable): RepositoryError.StorageFailure =
    RepositoryError.StorageFailure(safeMessage(error))

  def sequence[A](
      values: List[Either[RepositoryError, A]]
  ): Either[RepositoryError, List[A]] =
    values.foldRight(Right(Nil): Either[RepositoryError, List[A]]) { (next, acc) =>
      for
        value <- next
        rest <- acc
      yield value :: rest
    }

  private def toRepositoryError(
      error: Throwable,
      uniqueConflictMessage: Option[String]
  ): RepositoryError =
    if isUniqueViolation(error) then
      RepositoryError.Conflict(
        uniqueConflictMessage.getOrElse("Unique constraint violation")
      )
    else
      storageFailure(error)

  private def isUniqueViolation(error: Throwable): Boolean =
    error match
      case sql: SQLException if sql.getSQLState == "23505" => true
      case _ => Option(error.getCause).exists(isUniqueViolation)

  private def safeMessage(error: Throwable): String =
    Option(error.getMessage)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(error.getClass.getSimpleName)
