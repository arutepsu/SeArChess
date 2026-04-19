package chess.adapter.rest.contract.dto

/** Request body for `POST /games/{gameId}/resign`.
 *
 *  @param side the resigning side: "White" or "Black"
 */
final case class ResignRequest(side: String)

object ResignRequest:
  /** Parse from a JSON string.
   *
   *  `side` is required; returns Left when absent or when the JSON is malformed.
   */
  def fromJson(body: String): Either[String, ResignRequest] =
    try
      val obj = ujson.read(body).obj
      Right(ResignRequest(side = obj("side").str))
    catch case _: Exception =>
      Left("Malformed JSON or missing required field 'side'")
