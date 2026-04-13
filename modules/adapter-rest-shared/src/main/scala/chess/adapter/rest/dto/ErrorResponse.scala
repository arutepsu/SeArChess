package chess.adapter.rest.dto

import ujson.Value

/** Transport representation of an API error.
 *
 *  @param code    machine-readable error key (e.g. "SESSION_NOT_FOUND")
 *  @param message human-readable detail
 */
final case class ErrorResponse(code: String, message: String)

object ErrorResponse:
  def toJson(r: ErrorResponse): Value =
    ujson.Obj(
      "code"    -> ujson.Str(r.code),
      "message" -> ujson.Str(r.message)
    )
