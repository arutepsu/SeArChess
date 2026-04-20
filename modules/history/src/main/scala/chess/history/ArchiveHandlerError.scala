package chess.history

import chess.application.session.model.SessionIds.GameId

/** Errors that [[ArchiveOnTerminalEventHandler.handle]] may return.
 *
 *  === Retryability ===
 *  - [[SnapshotNotClosed]]      — retryable; the session lifecycle has not yet
 *                                 reached `Finished`.  This is a race between the
 *                                 terminal event and the session close write; the
 *                                 handler should back off and retry.
 *  - [[SnapshotNotFound]]       — not retryable; no session exists for this game id.
 *  - [[SnapshotStorageFailure]] — retryable; transient infrastructure error on read.
 *  - [[MaterializationFailed]]  — not retryable without code change; the notation
 *                                 engine returned an unexpected failure for this state.
 *  - [[PersistenceFailed]]      — retryable; transient infrastructure error on write.
 */
enum ArchiveHandlerError:
  case SnapshotNotClosed(gameId: GameId)
  case SnapshotNotFound(gameId: GameId)
  case SnapshotStorageFailure(reason: String)
  case MaterializationFailed(error: ArchiveMaterializeError)
  case PersistenceFailed(reason: String)
