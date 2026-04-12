package chess.application.port.ai

/** Errors produced by an [[AIProvider]].
 *
 *  - [[NoLegalMove]]: the current position has no legal moves (terminal state).
 *    Callers should detect this condition via domain status before invoking the
 *    provider, but providers may also return it as a defensive signal.
 *  - [[EngineFailure]]: the engine encountered an unexpected condition and
 *    could not produce a candidate move.
 */
enum AIError:
  case NoLegalMove
  case EngineFailure(message: String)
