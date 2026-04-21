package chess.history

import chess.application.session.model.SessionIds.GameId

/** Outbound persistence port for [[ArchiveRecord]] storage.
 *
 *  The single required operation is [[upsert]]: callers do not need to check
 *  whether a record already exists; the repository guarantees last-write-wins
 *  semantics keyed on [[ArchiveRecord.gameId]].
 *
 *  Idempotency guarantee: calling [[upsert]] with the same [[gameId]] multiple
 *  times must succeed and leave the store in a consistent state.  This allows
 *  [[chess.history.ArchiveOnTerminalEventHandler]] to retry safely on transient
 *  failures without a separate "exists?" check.
 *
 *  Implementations belong in adapter modules; the history module depends only
 *  on this trait.
 */
trait ArchiveRepository:

  /** Persist (or replace) the archive record for [[record.gameId]].
   *
   *  Returns [[ArchiveRepositoryError.StorageFailure]] on infrastructure error.
   */
  def upsert(record: ArchiveRecord): Either[ArchiveRepositoryError, Unit]

  def findByGameId(gameId: GameId): Either[ArchiveRepositoryError, Option[ArchiveRecord]]
