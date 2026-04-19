package chess.adapter.rest.contract.dto

import ujson.Value

/** Transport representation of a list of active sessions.
 *
 *  @param sessions ordered list of [[SessionResponse]] items
 */
final case class SessionListResponse(sessions: List[SessionResponse])

object SessionListResponse:
  def toJson(r: SessionListResponse): Value =
    ujson.Obj(
      "sessions" -> ujson.Arr.from(r.sessions.map(SessionResponse.toJson))
    )
