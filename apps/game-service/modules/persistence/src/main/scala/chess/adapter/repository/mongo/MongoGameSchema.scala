package chess.adapter.repository.mongo

import chess.application.port.repository.RepositoryError
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import org.bson.Document

import scala.util.control.NonFatal

object MongoGameSchema:

  def initialize(collection: MongoCollection[Document]): Either[RepositoryError, Unit] =
    try
      collection.createIndex(Indexes.ascending("gameId"), IndexOptions().unique(true))
      Right(())
    catch case NonFatal(e) =>
      Left(RepositoryError.StorageFailure(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))
