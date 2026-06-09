package chess.adapter.ai.remote

/** Stable internal Game -> AI HTTP boundary metadata.
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
)

/** Optional engine selection/configuration sent to the remote AI service. */
final case class RemoteAiEngineSelection(
    engineId: Option[String]
)

/** Bounded execution limits for a remote AI suggestion request. */
final case class RemoteAiLimits(
    timeoutMillis: Int
)

/** Small metadata block for diagnostics and future routing. */
final case class RemoteAiMetadata(
    mode: String
)

/** Request DTO for the internal AI move-suggestion API. */
final case class RemoteAiMoveSuggestionRequest(
    requestId: String,
    gameId: String,
    sessionId: String,
    sideToMove: String,
    fen: String,
    legalMoves: List[RemoteAiMoveDto],
    engine: RemoteAiEngineSelection,
    limits: RemoteAiLimits,
    metadata: RemoteAiMetadata
)

/** Response DTO for a successful internal AI suggestion. */
final case class RemoteAiMoveSuggestionResponse(
    requestId: String,
    move: RemoteAiMoveDto,
    engineId: Option[String] = None,
    engineVersion: Option[String] = None,
    elapsedMillis: Option[Int] = None,
    confidence: Option[Double] = None
)

/** Error DTO for an internal AI service error response. */
final case class RemoteAiErrorResponse(
    requestId: String,
    code: String,
    message: String
)
