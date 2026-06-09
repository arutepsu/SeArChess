package chess.lichessbridge

/** Pure turn-detection logic. No IO, no side effects; fully testable in isolation.
  *
  * Lichess UCI move string conventions:
  *   - moves = "" → game start, no moves played
  *   - moves = "e2e4" → one move played (White moved)
  *   - moves = "e2e4 e7e5" → two moves (White then Black)
  *
  * White is always the first mover regardless of player assignment.
  * moveCount % 2 == 0 → White to move; moveCount % 2 == 1 → Black to move.
  */
object TurnDetector:

  private val TerminalStatuses: Set[String] = Set(
    "mate", "resign", "stalemate", "timeout", "draw", "outoftime",
    "cheat", "noStart", "unknownFinish", "variantEnd", "aborted"
  )

  def isTerminal(status: String): Boolean = TerminalStatuses.contains(status)

  /** True if it is the bot's turn to move. False for terminal statuses, unknown sides, or wrong parity. */
  def isBotTurn(botSide: String, moveCount: Int, status: String): Boolean =
    !isTerminal(status) && (botSide.toLowerCase match
      case "white" => moveCount % 2 == 0
      case "black" => moveCount % 2 == 1
      case _       => false
    )

  /** Count the number of moves in a space-separated UCI moves string. */
  def countMoves(movesStr: String): Int =
    if movesStr.isBlank then 0
    else movesStr.split(' ').count(_.nonEmpty)

  /** Determine which side the bot plays by matching botUsername against the white/black player names.
    * Case-insensitive. Returns None if the bot is not a participant.
    */
  def determineBotSide(botUsername: String, white: String, black: String): Option[String] =
    if botUsername.equalsIgnoreCase(white) then Some("white")
    else if botUsername.equalsIgnoreCase(black) then Some("black")
    else None
