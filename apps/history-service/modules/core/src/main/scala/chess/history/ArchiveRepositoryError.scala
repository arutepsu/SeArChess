package chess.history

/** Errors that an [[ArchiveRepository]] may return.
 *
 *  Mirrors the broad vocabulary of storage repository errors, but stays scoped
 *  to the history module so callers have no dependency on Game Service
 *  repository internals.
 */
enum ArchiveRepositoryError:
  case StorageFailure(message: String)
