package chess.history

/** Errors that can occur during archive record materialization.
  *
  * Both variants wrap the underlying [[chess.notation.api.NotationFailure]] message as a string so
  * that callers do not need a dependency on the notation module to handle them.
  *
  *   - [[FenExportFailed]] — FEN serialization of the final position failed
  *   - [[PgnExportFailed]] — PGN movetext or header assembly failed
  */
enum ArchiveMaterializeError:
  case FenExportFailed(reason: String)
  case PgnExportFailed(reason: String)
