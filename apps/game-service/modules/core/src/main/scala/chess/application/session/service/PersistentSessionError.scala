package chess.application.session.service

import chess.application.session.model.SessionIds.SessionId

/** Errors returned by [[PersistentSessionService]].
  *
  * These errors form the application boundary for persistence-oriented Web UI flows. Transport
  * adapters map them to HTTP status codes and wire-level error bodies.
  */
enum PersistentSessionError:
  /** The caller supplied invalid input before any repository call was attempted. */
  case BadInput(message: String)

  /** No session with the requested [[SessionId]] exists. */
  case NotFound(sessionId: SessionId)

  /** The requested operation conflicts with current persisted state or lifecycle rules. */
  case Conflict(message: String)

  /** The stored session and game state no longer form a valid aggregate. */
  case AggregateInconsistent(message: String)

  /** Persistence failed for an infrastructure reason. */
  case StorageFailure(message: String)
