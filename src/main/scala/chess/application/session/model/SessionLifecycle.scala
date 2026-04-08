package chess.application.session.model

/** Orchestration lifecycle of a [[GameSession]].
 *
 *  This is independent of [[chess.domain.model.GameStatus]], which tracks
 *  chess-rule outcomes (ongoing, check, checkmate, draw).  `SessionLifecycle`
 *  tracks the application's view of where the session is in its overall arc.
 *
 *  - [[Created]]: session has been initialised but the first move has not yet
 *    been made.  Controllers have been assigned; clock (if any) has not started.
 *
 *  - [[Active]]: the game is in progress.  A move from the current controller
 *    is expected.
 *
 *  - [[AwaitingPromotion]]: a pawn has reached the back rank.  The session is
 *    paused waiting for the current controller to supply a promotion piece
 *    before the move can be committed.  This state is distinct from [[Active]]
 *    so that session policy and UI can surface the promotion prompt explicitly
 *    without embedding that concern in the domain layer.
 *
 *  - [[Finished]]: the game has ended (checkmate, draw, resignation, timeout,
 *    abandonment).  No further moves are accepted.  The outcome is recorded in
 *    the domain [[chess.domain.model.GameStatus]]; [[Finished]] here signals
 *    that the session-layer cleanup (scoring, rematch offer, persistence) may
 *    proceed.
 */
enum SessionLifecycle:
  case Created
  case Active
  case AwaitingPromotion
  case Finished
