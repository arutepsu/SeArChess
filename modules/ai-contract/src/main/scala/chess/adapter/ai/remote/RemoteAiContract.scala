package chess.adapter.ai.remote

/** Stable internal Game -> AI HTTP boundary metadata.
<<<<<<< HEAD
  *
  * This module is the neutral wire contract shared by Game Service's outbound AI adapter and AI
  * Service's inbound HTTP routes. It must not depend on Game Service orchestration, domain rules,
  * or AI engine implementation.
  */
object RemoteAiServiceContract:
  val Version: String = "inference-api-v1"
  val Audience: String = "internal"
  val Interaction: String = "synchronous-http"

  val MoveSuggestionsPath: String = "/v1/move-suggestions"
  val HealthPath: String = "/health"

/** Move DTO used by the remote AI service contract. */
final case class RemoteAiMoveDto(
    from: String,
    to: String,
    promotion: Option[String] = None
=======
 *
 *  This module is the neutral wire contract shared by Game Service's outbound
 *  AI adapter and AI Service's inbound HTTP routes. It must not depend on
 *  Game Service orchestration, domain rules, or AI engine implementation.
 */
object RemoteAiServiceContract:
  val Version:     String = "inference-api-v1"
  val Audience:    String = "internal"
  val Interaction: String = "synchronous-http"

  val MoveSuggestionsPath: String = "/v1/move-suggestions"
  val HealthPath:          String = "/health"

/** Move DTO used by the remote AI service contract. */
final case class RemoteAiMoveDto(
  from:      String,
  to:        String,
  promotion: Option[String] = None
>>>>>>> ce08c01e (local microservices)
)

/** Optional engine selection/configuration sent to the remote AI service. */
final case class RemoteAiEngineSelection(
<<<<<<< HEAD
    engineId: Option[String]
=======
  engineId: Option[String]
>>>>>>> ce08c01e (local microservices)
)

/** Bounded execution limits for a remote AI suggestion request. */
final case class RemoteAiLimits(
<<<<<<< HEAD
    timeoutMillis: Int
=======
  timeoutMillis: Int
>>>>>>> ce08c01e (local microservices)
)

/** Small metadata block for diagnostics and future routing. */
final case class RemoteAiMetadata(
<<<<<<< HEAD
    mode: String
=======
  mode: String
>>>>>>> ce08c01e (local microservices)
)

/** Request DTO for the internal AI move-suggestion API. */
final case class RemoteAiMoveSuggestionRequest(
<<<<<<< HEAD
    requestId: String,
    gameId: String,
    sessionId: String,
    sideToMove: String,
    fen: String,
    legalMoves: List[RemoteAiMoveDto],
    engine: RemoteAiEngineSelection,
    limits: RemoteAiLimits,
    metadata: RemoteAiMetadata
=======
  requestId:  String,
  gameId:     String,
  sessionId:  String,
  sideToMove: String,
  fen:        String,
  legalMoves: List[RemoteAiMoveDto],
  engine:     RemoteAiEngineSelection,
  limits:     RemoteAiLimits,
  metadata:   RemoteAiMetadata
>>>>>>> ce08c01e (local microservices)
)

/** Response DTO for a successful internal AI suggestion. */
final case class RemoteAiMoveSuggestionResponse(
<<<<<<< HEAD
    requestId: String,
    move: RemoteAiMoveDto,
    engineId: Option[String] = None,
    engineVersion: Option[String] = None,
    elapsedMillis: Option[Int] = None,
    confidence: Option[Double] = None
=======
  requestId:     String,
  move:          RemoteAiMoveDto,
  engineId:      Option[String] = None,
  engineVersion: Option[String] = None,
  elapsedMillis: Option[Int]    = None,
  confidence:    Option[Double] = None
>>>>>>> ce08c01e (local microservices)
)

/** Error DTO for an internal AI service error response. */
final case class RemoteAiErrorResponse(
<<<<<<< HEAD
    requestId: String,
    code: String,
    message: String
=======
  requestId: String,
  code:      String,
  message:   String
>>>>>>> ce08c01e (local microservices)
)
