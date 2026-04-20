package chess.history

import chess.application.ArchiveError
import chess.application.GameServiceApi
import chess.application.event.AppEvent
import chess.application.session.model.SessionIds.GameId

/** Orchestrates archive materialization in response to terminal Game Service events.
 *
 *  === Event routing ===
 *  Only three [[AppEvent]] variants trigger materialization:
 *  - [[AppEvent.GameFinished]]     — checkmate or draw
 *  - [[AppEvent.GameResigned]]     — player resignation
 *  - [[AppEvent.SessionCancelled]] — administrative session termination
 *
 *  All other events are silently ignored and return `Right(None)`.
 *  This allows the handler to be wired to a fan-out publisher that delivers
 *  every application event without pre-filtering.
 *
 *  === Materialization pipeline ===
 *  {{{
 *    terminal event received
 *      → getArchiveSnapshot(gameId)   [Game Service query]
 *      → materializer.materialize(snapshot)
 *      → repository.upsert(record)
 *      → Right(Some(record))
 *  }}}
 *
 *  === Error semantics ===
 *  See [[ArchiveHandlerError]] for the retryability classification of each
 *  error variant.  The handler itself does not retry — retry policy belongs
 *  at the call site (future History service subscriber).
 *
 *  === Future wiring ===
 *  In a full History service this handler sits behind an event subscriber
 *  (message broker consumer or in-process fan-out).  The subscriber calls
 *  [[handle]] for each delivered event, inspects the result, and applies
 *  the appropriate retry / dead-letter policy based on [[ArchiveHandlerError]].
 *
 *  @param gameService  Game Service query boundary; used only for `getArchiveSnapshot`
 *  @param materializer converts a [[chess.application.query.game.GameArchiveSnapshot]]
 *                      into an [[ArchiveRecord]] with FEN and PGN
 *  @param repository   persistence port for the resulting [[ArchiveRecord]]
 */
class ArchiveOnTerminalEventHandler(
  gameService:  GameServiceApi,
  materializer: ArchiveMaterializer,
  repository:   ArchiveRepository
):

  /** Handle one [[AppEvent]].
   *
   *  @return `Right(Some(record))` — event was terminal and archive was upserted
   *          `Right(None)`         — event was not a terminal event; nothing to do
   *          `Left(error)`         — processing failed; see [[ArchiveHandlerError]]
   */
  def handle(event: AppEvent): Either[ArchiveHandlerError, Option[ArchiveRecord]] =
    terminalGameId(event) match
      case None         => Right(None)
      case Some(gameId) => materializeAndPersist(gameId)

  // ── Private helpers ──────────────────────────────────────────────────────────

  private def materializeAndPersist(
    gameId: GameId
  ): Either[ArchiveHandlerError, Option[ArchiveRecord]] =
    for
      snapshot <- gameService.getArchiveSnapshot(gameId)
                    .left.map {
                      case ArchiveError.GameNotClosed(id)   => ArchiveHandlerError.SnapshotNotClosed(id)
                      case ArchiveError.GameNotFound(id)    => ArchiveHandlerError.SnapshotNotFound(id)
                      case ArchiveError.StorageFailure(msg) => ArchiveHandlerError.SnapshotStorageFailure(msg)
                    }
      record   <- materializer.materialize(snapshot)
                    .left.map(ArchiveHandlerError.MaterializationFailed(_))
      _        <- repository.upsert(record)
                    .left.map {
                      case ArchiveRepositoryError.StorageFailure(msg) =>
                        ArchiveHandlerError.PersistenceFailed(msg)
                    }
    yield Some(record)

  private def terminalGameId(event: AppEvent): Option[GameId] = event match
    case AppEvent.GameFinished(_, gameId, _)  => Some(gameId)
    case AppEvent.GameResigned(_, gameId, _)  => Some(gameId)
    case AppEvent.SessionCancelled(_, gameId) => Some(gameId)
    case _                                    => None
