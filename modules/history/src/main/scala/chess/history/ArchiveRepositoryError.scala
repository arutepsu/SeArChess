package chess.history

/** Errors that an [[ArchiveRepository]] may return.
 *
 *  Mirrors the vocabulary of `chess.application.port.repository.RepositoryError`
 *  but scoped to the history module so callers have no dependency on the
 *  application persistence port.
 */
enum ArchiveRepositoryError:
  case StorageFailure(message: String)
