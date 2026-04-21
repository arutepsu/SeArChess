package chess.adapter.rest.contract.dto

import ujson.Value

/** Canonical command response body for Game commands that return updated state.
  *
  * @param game
  *   updated game state after the move
  * @param sessionLifecycle
  *   updated session lifecycle phase after the move. Possible values: "Active" | "Finished".
  *
  * ===REST v1 lifecycle note===
  * `"AwaitingPromotion"` will never appear in this response. REST v1 uses an error-driven promotion
  * flow: a move to the back rank without a promotion piece is rejected with 422 PROMOTION_REQUIRED
  * before the session lifecycle is updated. The client must re-submit the move with the
  * `"promotion"` field populated; that second submission succeeds and returns `"Active"` (or
  * `"Finished"` if it delivers checkmate).
  */
final case class CommandGameResponse(game: GameSnapshot, sessionLifecycle: String)

object CommandGameResponse:
  def toJson(r: CommandGameResponse): Value =
    ujson.Obj(
      "game" -> GameSnapshot.toJson(r.game),
      "sessionLifecycle" -> ujson.Str(r.sessionLifecycle)
    )

/** Temporary source-compatible alias for pre-cleanup DTO naming.
  *
  * New code should use [[CommandGameResponse]]. The JSON wire shape is unchanged.
  */
type SubmitMoveResponse = CommandGameResponse

object SubmitMoveResponse:
  def apply(game: GameSnapshot, sessionLifecycle: String): CommandGameResponse =
    CommandGameResponse(game, sessionLifecycle)

  def toJson(r: CommandGameResponse): Value =
    CommandGameResponse.toJson(r)
