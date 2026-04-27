package chess.application.port.ai

/** Errors produced by an [[AiMoveSuggestionClient]].
  *
  *   - [[NoLegalMove]]: the current position has no legal moves (terminal state). Callers should
  *     detect this condition via domain status before invoking the client, but clients may also
  *     return it as a defensive signal.
  *   - [[Unavailable]]: the remote AI service could not be reached.
  *   - [[Timeout]]: the remote AI service did not answer inside the configured client timeout.
  *   - [[MalformedResponse]]: the remote AI service answered, but the response could not be decoded
  *     into the shared inference contract.
  *   - [[EngineFailure]]: the engine returned an explicit inference failure.
  */
enum AIError:
  case NoLegalMove
  case Unavailable(message: String)
  case Timeout(message: String)
  case MalformedResponse(message: String)
  case EngineFailure(message: String)
