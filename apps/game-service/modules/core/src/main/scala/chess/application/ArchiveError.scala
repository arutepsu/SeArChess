package chess.application

import chess.application.session.model.SessionIds.GameId

/** Errors that can occur when retrieving an archive snapshot.
  *
  *   - [[GameNotFound]] — no game or session exists for the given [[GameId]]
  *   - [[GameNotClosed]] — the session exists but is not yet finished; the snapshot is only
  *     available once the session has reached `SessionLifecycle.Finished`
  *   - [[StorageFailure]] — infrastructure error during lookup
  */
enum ArchiveError:
  case GameNotFound(id: GameId)
  case GameNotClosed(id: GameId)
  case StorageFailure(msg: String)
