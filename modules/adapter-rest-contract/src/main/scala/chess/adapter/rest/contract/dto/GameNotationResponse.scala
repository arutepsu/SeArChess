package chess.adapter.rest.contract.dto

import ujson.Value

/** Canonical notation response for a game position.
  *
  * @param fen
  *   Forsyth-Edwards Notation for the current position
  * @param pgn
  *   PGN movetext for the game so far (no headers)
  */
final case class GameNotationResponse(fen: String, pgn: String)

object GameNotationResponse:
  def toJson(r: GameNotationResponse): Value =
    ujson.Obj(
      "fen" -> ujson.Str(r.fen),
      "pgn" -> ujson.Str(r.pgn)
    )
