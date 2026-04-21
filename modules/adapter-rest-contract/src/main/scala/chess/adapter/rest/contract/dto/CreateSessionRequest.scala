package chess.adapter.rest.contract.dto

/** Canonical request body for creating a playable game via POST /sessions.
  *
  * All fields are optional; absent fields use mode-aware defaults in the Game Service mapping
  * layer:
  *   - HumanVsHuman: White HumanLocal, Black HumanLocal
  *   - HumanVsAI: White HumanLocal, Black server AI
  *   - AIVsAI: White server AI, Black server AI
  *
  * @param mode
  *   "HumanVsHuman" | "HumanVsAI" | "AIVsAI"
  * @param whiteController
  *   "HumanLocal" | "HumanRemote" for human-controlled seats only
  * @param blackController
  *   "HumanLocal" | "HumanRemote" for human-controlled seats only
  *
  * ===REST v1 controller constraint===
  * "AI" is not a valid controller value in this API. Passing "AI" returns 400 BAD_REQUEST.
  * Server-side AI seats are derived from `mode`; the client does not configure AI engine identity
  * via this endpoint.
  */
final case class CreateGameRequest(
    mode: Option[String],
    whiteController: Option[String],
    blackController: Option[String]
)

object CreateGameRequest:
  /** Parse from a JSON string. Returns Left with a message if the JSON is malformed. Missing fields
    * are treated as None (not an error).
    */
  def fromJson(body: String): Either[String, CreateGameRequest] =
    try
      val json = ujson.read(body)
      Right(
        CreateGameRequest(
          mode = json.objOpt.flatMap(_.get("mode")).map(_.str),
          whiteController = json.objOpt.flatMap(_.get("whiteController")).map(_.str),
          blackController = json.objOpt.flatMap(_.get("blackController")).map(_.str)
        )
      )
    catch case _: Exception => Left("Malformed JSON in request body")

/** Temporary source-compatible alias for pre-cleanup DTO naming.
  *
  * New code should use [[CreateGameRequest]]. The endpoint and JSON wire shape remain `POST
  * /sessions` with the same request fields.
  */
type CreateSessionRequest = CreateGameRequest

object CreateSessionRequest:
  def apply(
      mode: Option[String],
      whiteController: Option[String],
      blackController: Option[String]
  ): CreateGameRequest =
    CreateGameRequest(mode, whiteController, blackController)

  def fromJson(body: String): Either[String, CreateGameRequest] =
    CreateGameRequest.fromJson(body)
