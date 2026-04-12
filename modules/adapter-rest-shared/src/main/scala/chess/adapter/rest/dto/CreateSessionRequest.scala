package chess.adapter.rest.dto

/** Request body for POST /sessions.
 *
 *  All fields are optional; absent fields use defaults in the mapper layer:
 *  - mode            → HumanVsHuman
 *  - whiteController → HumanLocal
 *  - blackController → HumanLocal
 *
 *  @param mode            "HumanVsHuman" | "HumanVsAI" | "AIVsAI"
 *  @param whiteController "HumanLocal" | "HumanRemote"
 *  @param blackController "HumanLocal" | "HumanRemote"
 */
final case class CreateSessionRequest(
  mode:            Option[String],
  whiteController: Option[String],
  blackController: Option[String]
)

object CreateSessionRequest:
  /** Parse from a JSON string.  Returns Left with a message if the JSON is
   *  malformed.  Missing fields are treated as None (not an error).
   */
  def fromJson(body: String): Either[String, CreateSessionRequest] =
    try
      val json = ujson.read(body)
      Right(CreateSessionRequest(
        mode            = json.objOpt.flatMap(_.get("mode")).map(_.str),
        whiteController = json.objOpt.flatMap(_.get("whiteController")).map(_.str),
        blackController = json.objOpt.flatMap(_.get("blackController")).map(_.str)
      ))
    catch case _: Exception => Left("Malformed JSON in request body")
