package chess.adapter.rest.dto

import ujson.Value

/** Response body for POST /games/{gameId}/moves.
 *
 *  @param game             updated game state after the move
 *  @param sessionLifecycle updated session lifecycle phase after the move.
 *                          Possible values: "Active" | "Finished".
 *
 *  === REST v1 lifecycle note ===
 *  `"AwaitingPromotion"` will never appear in this response.  REST v1 uses
 *  an error-driven promotion flow: a move to the back rank without a
 *  promotion piece is rejected with 422 PROMOTION_REQUIRED before the
 *  session lifecycle is updated.  The client must re-submit the move with the
 *  `"promotion"` field populated; that second submission succeeds and returns
 *  `"Active"` (or `"Finished"` if it delivers checkmate).
 */
final case class SubmitMoveResponse(game: GameResponse, sessionLifecycle: String)

object SubmitMoveResponse:
  def toJson(r: SubmitMoveResponse): Value =
    ujson.Obj(
      "game"             -> GameResponse.toJson(r.game),
      "sessionLifecycle" -> ujson.Str(r.sessionLifecycle)
    )
