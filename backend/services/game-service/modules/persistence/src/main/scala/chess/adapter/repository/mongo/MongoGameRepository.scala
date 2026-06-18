package chess.adapter.repository.mongo

import chess.application.port.repository.{GameRepository, RepositoryError}
import chess.application.session.model.SessionIds.GameId
import chess.domain.state.GameState
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document

import scala.util.control.NonFatal

class MongoGameRepository(collection: MongoCollection[Document]) extends GameRepository:

  override def save(gameId: GameId, state: GameState): Either[RepositoryError, Unit] =
    try
      collection.replaceOne(
        Filters.eq("gameId", gameId.value.toString),
        MongoGameStateMapper.toDocument(gameId, state),
        ReplaceOptions().upsert(true)
      )
      Right(())
    catch case NonFatal(e) =>
      Left(RepositoryError.StorageFailure(messageOf(e)))

  override def load(gameId: GameId): Either[RepositoryError, GameState] =
    try
      Option(collection.find(Filters.eq("gameId", gameId.value.toString)).first()) match
        case Some(document) => MongoGameStateMapper.toGameState(document)
        case None           => Left(RepositoryError.NotFound(gameId.value.toString))
    catch case NonFatal(e) =>
      Left(RepositoryError.StorageFailure(messageOf(e)))

  private def messageOf(error: Throwable): String =
    Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
