package chess.adapter.rest.contract.dto

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
 *
 *  === REST v1 controller constraint ===
 *  "AI" is not a valid controller value in this API.  Passing "AI" returns
 *  400 BAD_REQUEST.  Server-side AI (for HumanVsAI / AIVsAI modes) is
 *  triggered externally; the client does not configure the AI engine identity
 *  via this endpoint.
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
