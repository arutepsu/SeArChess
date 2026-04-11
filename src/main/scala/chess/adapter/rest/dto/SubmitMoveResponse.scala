package chess.adapter.rest.dto

import ujson.Value

/** Response body for POST /games/{gameId}/moves.
 *
 *  @param game             updated game state after the move
 *  @param sessionLifecycle updated session lifecycle phase (e.g. "Active",
 *                          "AwaitingPromotion", "Finished")
 */
final case class SubmitMoveResponse(game: GameResponse, sessionLifecycle: String)

object SubmitMoveResponse:
  def toJson(r: SubmitMoveResponse): Value =
    ujson.Obj(
      "game"             -> GameResponse.toJson(r.game),
      "sessionLifecycle" -> ujson.Str(r.sessionLifecycle)
    )
