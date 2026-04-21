package chess.history

import chess.application.session.model.SessionIds.GameId
import scala.collection.mutable

/** In-memory [[ArchiveRepository]] for tests.
  *
  * Stores records keyed by [[ArchiveRecord.gameId]]. [[upsert]] is always a silent overwrite — no
  * concurrency safety, intended for single-threaded test use only.
  */
class InMemoryArchiveRepository extends ArchiveRepository:

  private val store = mutable.Map.empty[GameId, ArchiveRecord]

  override def upsert(record: ArchiveRecord): Either[ArchiveRepositoryError, Unit] =
    store.update(record.gameId, record)
    Right(())

  override def findByGameId(gameId: GameId): Either[ArchiveRepositoryError, Option[ArchiveRecord]] =
    Right(store.get(gameId))

  def findInMemory(gameId: GameId): Option[ArchiveRecord] =
    store.get(gameId)

  def size: Int = store.size

  def all: List[ArchiveRecord] = store.values.toList
