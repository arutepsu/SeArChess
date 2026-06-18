package chess.history

import chess.application.query.game.GameArchiveSnapshot
import chess.application.session.model.SessionIds.GameId

/** Orchestrates archive materialization in response to terminal Game Service event triggers.
  *
  * The trigger is already reduced to [[TerminalGameEvent]], so this class does not import or
  * pattern-match on Game Service application internals.
  *
  * Pipeline:
  *   - terminal event received
  *   - fetch archive snapshot from Game Service
  *   - materialize FEN/PGN archive record
  *   - upsert into History storage
  */
class ArchiveOnTerminalEventHandler(
    fetchSnapshot: GameId => Either[ArchiveHandlerError, GameArchiveSnapshot],
    materializer: ArchiveMaterializer,
    repository: ArchiveRepository
):

  def handle(event: TerminalGameEvent): Either[ArchiveHandlerError, ArchiveRecord] =
    for
      snapshot <- fetchSnapshot(event.gameId)
      record <- materializer
        .materialize(snapshot)
        .left
        .map(ArchiveHandlerError.MaterializationFailed(_))
      _ <- repository.upsert(record).left.map { case ArchiveRepositoryError.StorageFailure(msg) =>
        ArchiveHandlerError.PersistenceFailed(msg)
      }
    yield record
