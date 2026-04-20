package chess.adapter.ai.remote

/** Move DTO used by the future remote AI service contract. */
final case class RemoteAiMoveDto(
  from:      String,
  to:        String,
  promotion: Option[String] = None
)

/** Optional engine selection/configuration sent to a remote AI provider. */
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

/** Adapter-level request DTO for the future remote AI move-suggestion API.
 *
 *  This is intentionally not an application-layer model. It belongs to the AI
 *  adapter boundary and can later be serialized by an HTTP client adapter.
 */
final case class RemoteAiMoveSuggestionRequest(
  requestId:  String,
  gameId:     String,
  sessionId:  String,
  sideToMove: String,
  fen:        String,
  legalMoves: List[RemoteAiMoveDto],
  engine:     RemoteAiEngineSelection,
  limits:     RemoteAiLimits,
  metadata:   RemoteAiMetadata
)

/** Adapter-level response DTO for a successful remote AI suggestion. */
final case class RemoteAiMoveSuggestionResponse(
  requestId:     String,
  move:          RemoteAiMoveDto,
  engineId:      Option[String] = None,
  engineVersion: Option[String] = None,
  elapsedMillis: Option[Int]    = None,
  confidence:    Option[Double] = None
)

/** Adapter-level error DTO for a future remote AI provider response. */
final case class RemoteAiErrorResponse(
  requestId: String,
  code:      String,
  message:   String
)
