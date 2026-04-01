package chess.notation.api

/** Closed set of notation formats the system supports.
 *
 *  Extend this enum (and add a corresponding [[NotationParser]] implementation)
 *  to support a new format.  Sealed so exhaustive pattern matching is enforced
 *  at every dispatch point.
 */
enum NotationFormat:
  case PGN
  case FEN
  case JSON
