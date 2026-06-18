package chess.application.migration

import chess.application.port.repository.{GameRepository, SessionGameStore, SessionRepository}
import chess.application.port.repository.RepositoryError

trait SessionMigrationReader:
  def readBatch(
      cursor: Option[SessionMigrationCursor],
      batchSize: Int
  ): Either[RepositoryError, SessionMigrationBatch]

final case class MigrationSourceAdapter(
    name: String,
    sessionReader: SessionMigrationReader,
    gameRepository: GameRepository
)

final case class MigrationTargetAdapter(
    name: String,
    sessionRepository: SessionRepository,
    gameRepository: GameRepository,
    store: SessionGameStore
)
