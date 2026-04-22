package chess.application.port.repository

/** Errors that a [[SessionRepository]] (or any future repository) may return.
  *
  * Kept at the port level so all repository traits share one error vocabulary. Application services
  * map these to their own error types before surfacing them to callers.
  */
enum RepositoryError:
  /** No record with the given identifier exists in the store. */
  case NotFound(id: String)

  /** The requested write conflicts with an existing persistence invariant. */
  case Conflict(message: String)

  /** The underlying store produced an unexpected failure. */
  case StorageFailure(message: String)
