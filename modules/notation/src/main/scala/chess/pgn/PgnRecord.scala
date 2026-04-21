package chess.notation.pgn

/** Parser-local PGN representation for the Stage-2 PGN core.
  *
  * Keeps only the information needed by the notation layer:
  *   - header tag pairs
  *   - mainline SAN tokens
  *   - optional result token
  */
private[pgn] final case class PgnRecord(
    headers: Map[String, String],
    moveTokens: Vector[String],
    result: Option[String]
)
