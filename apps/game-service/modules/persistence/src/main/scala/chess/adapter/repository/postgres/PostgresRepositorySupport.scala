package chess.adapter.repository.postgres

import chess.application.port.repository.RepositoryError
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api.Database

import java.sql.SQLException
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

private[postgres] object PostgresRepositorySupport:
  def run[A](
      db: Database,
      timeout: Duration
  )(
      action: DBIO[Either[RepositoryError, A]]
  ): Either[RepositoryError, A] =
    run(db, timeout, None)(action)

  def run[A](
      db: Database,
      timeout: Duration,
      uniqueConflictMessage: Option[String]
  )(
      action: DBIO[Either[RepositoryError, A]]
  ): Either[RepositoryError, A] =
    try Await.result(db.run(action), timeout)
    catch case NonFatal(error) => Left(toRepositoryError(error, uniqueConflictMessage))

  def runWithConflictMapping[A](
      db: Database,
      timeout: Duration,
      conflictMessage: String
  )(
      action: DBIO[Either[RepositoryError, A]]
  ): Either[RepositoryError, A] =
    run(db, timeout, Some(conflictMessage))(action)

  def storageFailure(message: String): RepositoryError.StorageFailure =
    RepositoryError.StorageFailure(message)

  def storageFailure(error: Throwable): RepositoryError.StorageFailure =
    RepositoryError.StorageFailure(safeMessage(error))

  def conflict(message: String): RepositoryError.Conflict =
    RepositoryError.Conflict(message)

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
