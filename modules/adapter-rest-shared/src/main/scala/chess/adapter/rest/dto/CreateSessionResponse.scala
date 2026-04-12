package chess.adapter.rest.dto

import ujson.Value

/** Response body for POST /sessions.
 *
 *  Bundles the new session metadata with the initial game state so the
 *  client has everything it needs to start interacting without a second
 *  round-trip.
 *
 *  @param session session metadata
 *  @param game    initial game state summary
 */
final case class CreateSessionResponse(session: SessionResponse, game: GameResponse)

object CreateSessionResponse:
  def toJson(r: CreateSessionResponse): Value =
    ujson.Obj(
      "session" -> SessionResponse.toJson(r.session),
      "game"    -> GameResponse.toJson(r.game)
    )
