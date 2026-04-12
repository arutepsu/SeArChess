package chess.application.ai.service

import chess.application.port.ai.AIError
import chess.application.session.service.SessionMoveError

/** Errors produced by [[AITurnService.requestAIMove]].
 *
 *  - [[NotAITurn]]: the side to move is not controlled by an AI; the service
 *    refuses to generate a move on behalf of a human side.
 *  - [[ProviderFailure]]: the [[chess.application.port.ai.AIProvider]] could not
 *    produce a move candidate (e.g. no legal moves, engine error).
 *  - [[MoveFailed]]: the AI's proposed move was rejected by the session or
 *    domain move path.  Under a correct provider implementation this should not
 *    happen; it is surfaced here so callers can handle provider bugs explicitly.
 */
enum AITurnError:
  case NotAITurn
  case ProviderFailure(cause: AIError)
  case MoveFailed(cause: SessionMoveError)
