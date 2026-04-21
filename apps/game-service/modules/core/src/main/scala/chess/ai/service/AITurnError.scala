package chess.application.ai.service

import chess.application.port.ai.AIError
import chess.application.port.repository.RepositoryError
import chess.application.session.service.{SessionError, SessionMoveError}

/** Errors produced by [[AITurnService.requestAIMove]] and
  * [[chess.application.GameServiceApi.triggerAIMove]].
  *
  * Cases are ordered from "caller error" to "infrastructure/provider error":
  *
  *   - [[NotConfigured]]: no AI service is wired into this deployment. The operation cannot be
  *     attempted at all.
  *   - [[NotAITurn]]: the current player's side is not AI-controlled. The AI turn guard rejected
  *     the request; this is a policy/state error, not an infrastructure error.
  *   - [[SessionLookupFailed]]: the session could not be loaded from the repository. Carries the
  *     underlying [[SessionError]] so callers can distinguish "not found" from a storage failure.
  *   - [[GameStateLookupFailed]]: the game state could not be loaded from the repository. Carries
  *     the underlying [[RepositoryError]].
  *   - [[ProviderFailure]]: the [[chess.application.port.ai.AiMoveSuggestionClient]] could not
  *     produce a move candidate (e.g. no legal moves, engine error).
  *   - [[IllegalSuggestedMove]]: the AI client returned a move that is not in the Game
  *     Service-computed legal move set.
  *   - [[MoveFailed]]: the AI's proposed move was rejected by the session or domain move path.
  *     Under a correct provider implementation this should not happen; it is surfaced here so
  *     callers can handle provider bugs explicitly.
  *
  * ===Future refinement note===
  * [[SessionLookupFailed]] and [[GameStateLookupFailed]] currently embed [[SessionError]] and
  * [[RepositoryError]] from lower application layers. This leaks internal boundary types into the
  * public service contract. A later cleanup should flatten these into self-contained cases at the
  * service-boundary level (e.g. `SessionNotFound(id)`, `GameStateNotFound(id)`,
  * `StorageFailure(msg)`) so that callers do not depend on the internal error hierarchy.
  */
enum AITurnError:
  /** No AI service is wired into this deployment;
    * [[chess.application.GameServiceApi.triggerAIMove]] is not available.
    */
  case NotConfigured

  /** The current player's side is not AI-controlled; the AI turn guard failed. */
  case NotAITurn

  /** The session could not be loaded from the repository. */
  case SessionLookupFailed(cause: SessionError)

  /** The game state could not be loaded from the repository. */
  case GameStateLookupFailed(cause: RepositoryError)

  /** The AI provider could not produce a move candidate. */
  case ProviderFailure(cause: AIError)

  /** The AI's proposed move was not in Game Service's legal move set. */
  case IllegalSuggestedMove(move: chess.domain.model.Move)

  /** The AI's proposed move was rejected by the domain or session policy. */
  case MoveFailed(cause: SessionMoveError)
