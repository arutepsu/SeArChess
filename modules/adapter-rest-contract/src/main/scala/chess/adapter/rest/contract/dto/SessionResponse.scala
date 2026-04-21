package chess.adapter.rest.contract.dto

import ujson.Value

/** Wire representation of a game session.
 *
 *  @param sessionId       opaque UUID string
 *  @param gameId          opaque UUID string of the associated game
 *  @param mode            "HumanVsHuman" | "HumanVsAI" | "AIVsAI"
 *  @param lifecycle       "Created" | "Active" | "AwaitingPromotion" | "Finished"
 *  @param whiteController "HumanLocal" | "HumanRemote" | "AI"
 *  @param blackController same values as whiteController
 *  @param createdAt       ISO-8601 UTC instant string
 *  @param updatedAt       ISO-8601 UTC instant string
 */
final case class SessionResponse(
  sessionId:       String,
  gameId:          String,
  mode:            String,
  lifecycle:       String,
  whiteController: String,
  blackController: String,
  createdAt:       String,
  updatedAt:       String
)

object SessionResponse:
  def toJson(r: SessionResponse): Value =
    ujson.Obj(
      "sessionId"       -> ujson.Str(r.sessionId),
      "gameId"          -> ujson.Str(r.gameId),
      "mode"            -> ujson.Str(r.mode),
      "lifecycle"       -> ujson.Str(r.lifecycle),
      "whiteController" -> ujson.Str(r.whiteController),
      "blackController" -> ujson.Str(r.blackController),
      "createdAt"       -> ujson.Str(r.createdAt),
      "updatedAt"       -> ujson.Str(r.updatedAt)
    )
