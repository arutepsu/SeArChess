package chess.adapter.repository.mongo

import chess.application.migration.{
  SessionMigrationBatch,
  SessionMigrationCursor,
  SessionMigrationReader
}
import chess.application.port.repository.RepositoryError
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import org.bson.Document

import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.control.NonFatal

class MongoSessionMigrationReader(collection: MongoCollection[Document]) extends SessionMigrationReader:

  override def readBatch(
      cursor: Option[SessionMigrationCursor],
      batchSize: Int
  ): Either[RepositoryError, SessionMigrationBatch] =
    try
      for
        lastSeenSessionId <- decodeCursor(cursor)
        documents <- loadDocuments(lastSeenSessionId, batchSize)
        pageDocuments = documents.take(batchSize)
        sessions <- sequence(pageDocuments.map(MongoSessionMapper.toSession))
      yield
        val nextCursor =
          if documents.size <= batchSize then None
          else sessions.lastOption.map(session =>
            SessionMigrationCursor(session.sessionId.value.toString)
          )
        SessionMigrationBatch(sessions, nextCursor)
    catch case NonFatal(error) =>
      Left(RepositoryError.StorageFailure(messageOf(error)))

  private def loadDocuments(
      lastSeenSessionId: Option[String],
      batchSize: Int
  ): Either[RepositoryError, List[Document]] =
    val query = lastSeenSessionId match
      case Some(sessionId) => Filters.gt("sessionId", sessionId)
      case None            => Document()

    val documents = collection
      .find(query)
      .sort(Sorts.ascending("sessionId"))
      .limit(batchSize + 1)
      .iterator()
      .asScala
      .toList

    if documents.isEmpty && lastSeenSessionId.nonEmpty then
      Left(
        RepositoryError.StorageFailure(
          "Session migration cursor does not reference any remaining Mongo sessions"
        )
      )
    else Right(documents)

  private def decodeCursor(
      cursor: Option[SessionMigrationCursor]
  ): Either[RepositoryError, Option[String]] =
    cursor match
      case None => Right(None)
      case Some(value) =>
        Try(UUID.fromString(value.value)).toEither.left
          .map(_ =>
            RepositoryError.StorageFailure(
              s"Invalid Mongo session migration cursor: ${value.value}"
            )
          )
          .map(uuid => Some(uuid.toString))

  private def sequence[A](values: List[Either[RepositoryError, A]]): Either[RepositoryError, List[A]] =
    values.foldRight(Right(Nil): Either[RepositoryError, List[A]]) { (next, acc) =>
      for
        value <- next
        rest <- acc
      yield value :: rest
    }

  private def messageOf(error: Throwable): String =
    Option(error.getMessage).getOrElse(error.getClass.getSimpleName)

object MongoSessionMigrationReader:
  def apply(collection: MongoCollection[Document]): MongoSessionMigrationReader =
    new MongoSessionMigrationReader(collection)
