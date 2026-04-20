package chess.application.query.game

import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, DrawReason, GameStatus}
import java.time.Instant

/** The reason a game session was closed.
 *
 *  Mirrors [[chess.domain.model.GameStatus]] for game-result closures, and adds
 *  [[Cancelled]] for administrative session termination where no game result was
 *  recorded (the underlying game state is left `Ongoing`).
 *
 *  This is the primary result carrier in [[GameArchiveSnapshot]].  Consumers
 *  should use `closure` rather than `finalState.status` to determine why a
 *  game ended, because cancelled sessions have `GameStatus.Ongoing` in the
 *  final state.
 */
sealed trait GameClosure
object GameClosure:
  final case class Checkmate(winner: Color)   extends GameClosure
  final case class Resigned(winner: Color)    extends GameClosure
  final case class Draw(reason: DrawReason)   extends GameClosure
  case object Cancelled                       extends GameClosure

  /** Derive the closure from the final game status.
   *
   *  An `Ongoing` status means the session was closed administratively
   *  (cancelled) rather than through a game result.
   */
  def fromStatus(status: GameStatus): GameClosure = status match
    case GameStatus.Checkmate(winner) => Checkmate(winner)
    case GameStatus.Resigned(winner)  => Resigned(winner)
    case GameStatus.Draw(reason)      => Draw(reason)
    case GameStatus.Ongoing(_)        => Cancelled

/** Archive/export snapshot for a completed or closed game session.
 *
 *  Produced by [[chess.application.GameServiceApi.getArchiveSnapshot]] once a
 *  session has reached `SessionLifecycle.Finished`.  This is the deliberate
 *  application-owned read contract that a future History or Notation service
 *  should consume rather than depending on raw [[chess.domain.state.GameState]].
 *
 *  ==Fields==
 *  - [[closure]]    — authoritative reason the game ended; use this, not
 *                     `finalState.status`, to determine the game result
 *  - [[finalState]] — full position snapshot at close time; carries move
 *                     history, board, clocks; `legalMoves` will be empty for
 *                     terminal positions
 *  - [[closedAt]]   — the session's `updatedAt` at the time it was finished
 */
final case class GameArchiveSnapshot(
  sessionId:       SessionId,
  gameId:          GameId,
  mode:            SessionMode,
  whiteController: SideController,
  blackController: SideController,
  closure:         GameClosure,
  finalState:      GameView,
  createdAt:       Instant,
  closedAt:        Instant
)
