package chess.application.session.policy

import chess.application.session.model.SessionLifecycle
import chess.application.session.model.SessionLifecycle.*

/** Pure policy that validates [[SessionLifecycle]] transitions.
  *
  * The allowed graph is deliberately narrow. Any transition not listed below is rejected. This
  * prevents sessions from being moved backward, skipping phases, or leaving a terminal [[Finished]]
  * or [[Cancelled]] state.
  *
  * Allowed transitions:
  * {{{
  *  Created           → Active               (first move received)
  *  Created           → Finished             (session aborted before it started)
  *  Active            → AwaitingPromotion    (pawn reached back rank)
  *  Active            → Finished             (checkmate, draw, resignation)
  *  AwaitingPromotion → Active               (promotion choice supplied)
  *  AwaitingPromotion → Finished             (session abandoned mid-promotion)
  * }}}
  *
  * Finished and Cancelled are terminal; no transition away from either is permitted.
  */
object SessionLifecyclePolicy:

  /** Validate that transitioning from `current` to `next` is permitted.
    *
    * Returns [[Right]] with `next` when the transition is allowed, or [[Left]] with a
    * human-readable rejection message when it is not.
    */
  def validateTransition(
      current: SessionLifecycle,
      next: SessionLifecycle
  ): Either[String, SessionLifecycle] =
    if allowed(current, next) then Right(next)
    else Left(s"Invalid lifecycle transition: $current → $next")

  private def allowed(from: SessionLifecycle, to: SessionLifecycle): Boolean =
    (from, to) match
      case (Created, Active)              => true
      case (Created, Finished)            => true
      case (Created, Cancelled)           => true
      case (Active, AwaitingPromotion)    => true
      case (Active, Finished)             => true
      case (Active, Cancelled)            => true
      case (AwaitingPromotion, Active)    => true
      case (AwaitingPromotion, Finished)  => true
      case (AwaitingPromotion, Cancelled) => true
      case _                              => false
