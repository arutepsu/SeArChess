package chess.adapter.rest.contract.dto

import scala.collection.mutable
import ujson.*

/** Request body for importing a game from FEN or PGN.
  *
  * @param format
  *   "FEN" or "PGN"
  * @param notation
  *   raw notation text
  * @param mode
  *   optional session mode override (defaults to HumanVsHuman)
  * @param whiteController
  *   optional controller for white (human only)
  * @param blackController
  *   optional controller for black (human only)
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
      val obj = json match
        case ujson.Obj(fields) => fields
        case _                 => mutable.LinkedHashMap.empty[String, ujson.Value]

      val format = obj.get("format").map(_.str)
      val notation = obj.get("notation").map(_.str)
      (format, notation) match
        case (Some(fmt), Some(text)) =>
          Right(
            ImportNotationRequest(
              format = fmt,
              notation = text,
              mode = obj.get("mode").map(_.str),
              whiteController = obj.get("whiteController").map(_.str),
              blackController = obj.get("blackController").map(_.str)
            )
          )
        case _ =>
          Left("format and notation are required")
    catch case _: Exception => Left("Malformed JSON in request body")
