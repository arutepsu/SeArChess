package chess.adapter.rest.contract.dto

import ujson.Value

/** Response body for notation export. */
final case class NotationResponse(format: String, notation: String)

object NotationResponse:
  def toJson(r: NotationResponse): Value =
    ujson.Obj(
      "format" -> ujson.Str(r.format),
      "notation" -> ujson.Str(r.notation)
    )

/** Request body for notation import into a new session.
  *
  * FEN/PGN notation is not a full session backup. It creates a fresh session from the imported
  * position or replayed game state and intentionally does not preserve Searchess session metadata.
  */
final case class ImportNotationRequest(
    format: String,
    notation: String,
    mode: Option[String],
    whiteController: Option[String],
    blackController: Option[String]
)

object ImportNotationRequest:
  def fromJson(body: String): Either[String, ImportNotationRequest] =
    try
      val json = ujson.read(body)
      Right(
        ImportNotationRequest(
          format = json("format").str,
          notation = json("notation").str,
          mode = json.objOpt.flatMap(_.get("mode")).map(_.str),
          whiteController = json.objOpt.flatMap(_.get("whiteController")).map(_.str),
          blackController = json.objOpt.flatMap(_.get("blackController")).map(_.str)
        )
      )
    catch case _: Exception => Left("Malformed JSON in request body")
