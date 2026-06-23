package chess.tournamentservice

/** Minimal description of a live public tournament game, sufficient for move generation.
  *
  * Sourced from the tournament-server game snapshot endpoint
  * (GET /api/tournament/{id}/game/{gameId}). The FEN encodes the current position
  * including the active color, so callers do not need to supply the active side separately.
  *
  * `fen` is the current board position. An empty or missing FEN (game not yet started)
  * will result in a [[MoveGenerationError.InvalidFen]] from [[PublicBotMoveGenerator]].
  *
  * `status` is the raw tournament-server status string ("ongoing", "finished", etc.).
  */
final case class PublicGameSnapshot(
  fen:    String,
  status: String
)
