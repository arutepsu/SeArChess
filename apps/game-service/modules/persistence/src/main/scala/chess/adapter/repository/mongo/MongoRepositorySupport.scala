package chess.adapter.repository.mongo

import chess.application.port.repository.RepositoryError
import com.mongodb.MongoWriteException

import scala.util.control.NonFatal

private[mongo] object MongoRepositorySupport:
  def protect[A](
      operation: => Either[RepositoryError, A],
      duplicateKeyMessage: Option[String] = None
  ): Either[RepositoryError, A] =
    try operation
    catch
      case error: MongoWriteException if isDuplicateKey(error) =>
        Left(
          RepositoryError.Conflict(
            duplicateKeyMessage.getOrElse(safeMessage(error))
          )
        )
      case NonFatal(error) =>
        Left(RepositoryError.StorageFailure(safeMessage(error)))

  def storageFailure(error: Throwable): RepositoryError.StorageFailure =
    RepositoryError.StorageFailure(safeMessage(error))

  def sequence[A](values: List[Either[RepositoryError, A]]): Either[RepositoryError, List[A]] =
    values.foldRight(Right(Nil): Either[RepositoryError, List[A]]) { (next, acc) =>
      for
        value <- next
        rest <- acc
      yield value :: rest
    }

  private def isDuplicateKey(error: MongoWriteException): Boolean =
    Option(error.getError).exists(_.getCode == 11000)

  private def safeMessage(error: Throwable): String =
    Option(error.getMessage)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(error.getClass.getSimpleName)
