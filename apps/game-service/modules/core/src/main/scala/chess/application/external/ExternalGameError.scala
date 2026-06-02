package chess.application.external

import chess.application.ai.service.AITurnError
import chess.application.port.repository.RepositoryError
import chess.application.session.model.ExternalPlatform
import chess.application.session.service.{SessionError, SessionMoveError}

/** Errors produced by [[ExternalGameService]] use cases.
  *
  * Cases are ordered from "caller error" to "infrastructure/provider error":
  *
  *   - [[BindingNotFound]]: no binding exists for the given `(platform, externalGameId)`.
  *   - [[AlreadyExists]]: a binding already exists when an exclusive-create was attempted.
  *   - [[Unauthorized]]: the [[VerifiedExternalCaller]] does not match `binding.ourActorId`.
  *   - [[BindingNotActive]]: the binding is Finished or Failed; further operations are rejected.
  *   - [[InvalidExternalMoveFormat]]: a UCI move string could not be parsed.
  *   - [[InvalidRequest]]: the request is structurally invalid (e.g. wrong mode for the operation).
  *   - [[SessionFailure]]: a session lifecycle or load operation failed.
  *   - [[RepositoryFailure]]: a persistence read/write failed.
  *   - [[AiFailure]]: the AI client could not produce a move candidate.
  *   - [[MoveRejected]]: the move was rejected by session policy or the chess domain.
  */
enum ExternalGameError:
  case BindingNotFound(platform: ExternalPlatform, externalGameId: String)
  case AlreadyExists(platform: ExternalPlatform, externalGameId: String)
  case Unauthorized(callerActorId: String, expectedActorId: String)
  case BindingNotActive(current: BindingStatus)
  case InvalidExternalMoveFormat(move: String, reason: String)
  case InvalidRequest(reason: String)
  case SessionFailure(cause: SessionError)
  case RepositoryFailure(cause: RepositoryError)
  case AiFailure(cause: AITurnError)
  case MoveRejected(cause: SessionMoveError)
