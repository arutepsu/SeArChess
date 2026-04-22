package chess.adapter.repository.mongo

import chess.application.port.repository.{RepositoryError, SessionRepository}
import chess.application.session.model.GameSession
import chess.application.session.model.SessionIds.{GameId, SessionId}
import com.mongodb.DuplicateKeyException
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

class MongoSessionRepository(collection: MongoCollection[Document]) extends SessionRepository:

  override def save(session: GameSession): Either[RepositoryError, Unit] =
    try
      val owner = Option(collection.find(Filters.eq("gameId", session.gameId.value.toString)).first())
      owner match
        case Some(existing) if existing.getString("sessionId") != session.sessionId.value.toString =>
          Left(
            RepositoryError.Conflict(
              s"GameId ${session.gameId.value} is already owned by session ${existing.getString("sessionId")}"
            )
          )
        case _ =>
          collection.replaceOne(
            Filters.eq("sessionId", session.sessionId.value.toString),
            MongoSessionMapper.toDocument(session),
            ReplaceOptions().upsert(true)
          )
          Right(())
    catch
      case _: DuplicateKeyException =>
        Left(RepositoryError.Conflict("GameId is already owned by another session"))
      case NonFatal(e) =>
        Left(RepositoryError.StorageFailure(messageOf(e)))

  override def load(id: SessionId): Either[RepositoryError, GameSession] =
    try
      Option(collection.find(Filters.eq("sessionId", id.value.toString)).first()) match
        case Some(document) => MongoSessionMapper.toSession(document)
        case None           => Left(RepositoryError.NotFound(id.value.toString))
    catch case NonFatal(e) => Left(RepositoryError.StorageFailure(messageOf(e)))

  override def loadByGameId(id: GameId): Either[RepositoryError, GameSession] =
    try
      Option(collection.find(Filters.eq("gameId", id.value.toString)).first()) match
        case Some(document) => MongoSessionMapper.toSession(document)
        case None           => Left(RepositoryError.NotFound(id.value.toString))
    catch case NonFatal(e) => Left(RepositoryError.StorageFailure(messageOf(e)))

  override def listActive(): Either[RepositoryError, List[GameSession]] =
    try
      val documents = collection
        .find(Filters.nin("lifecycle", "Finished", "Cancelled"))
        .iterator()
        .asScala
        .toList

      sequence(documents.map(MongoSessionMapper.toSession))
    catch case NonFatal(e) => Left(RepositoryError.StorageFailure(messageOf(e)))

  private def sequence[A](values: List[Either[RepositoryError, A]]): Either[RepositoryError, List[A]] =
    values.foldRight(Right(Nil): Either[RepositoryError, List[A]]) { (next, acc) =>
      for
        value <- next
        rest <- acc
      yield value :: rest
    }

  private def messageOf(error: Throwable): String =
    Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
