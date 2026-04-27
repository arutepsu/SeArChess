package chess.application.session.service

import chess.application.ApplicationError
import chess.application.session.model.{SessionLifecycle, SideController}
import chess.domain.model.Color

/** Errors produced by the session-aware move path ([[SessionLifecycleService.applyMove]]).
  *
  * Bundles session-level rejections and domain-level rejections under one type so callers get a
  * single `Either` branch without needing union types.
  *
  *   - [[SessionFinished]]: the session is in [[SessionLifecycle.Finished]]; no moves are accepted.
  *   - [[UnauthorizedController]]: the requesting controller does not own the side to move. The
  *     error carries the rejected controller and the color it tried to move for, allowing callers
  *     to surface a precise message.
  *   - [[DomainRejection]]: the move was authorized at the session level but rejected by the chess
  *     engine (illegal move, wrong turn, etc.).
  *   - [[PersistenceFailed]]: the post-move lifecycle update could not be saved.
  */
enum SessionMoveError:
  case SessionFinished
  case UnauthorizedController(requesting: SideController, sideToMove: Color)
  case DomainRejection(cause: ApplicationError)
  case PersistenceFailed(cause: SessionError)
