package chess.application.query.game

import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, DrawReason, GameStatus}
import java.time.Instant

/** The reason a game session was closed.
  *
  * This is the contract-facing closure value used by archive snapshots.
  */
sealed trait GameClosure
object GameClosure:
  final case class Checkmate(winner: Color) extends GameClosure
  final case class Resigned(winner: Color) extends GameClosure
  final case class Draw(reason: DrawReason) extends GameClosure
  case object Cancelled extends GameClosure

  /** Derive the closure from the final game status. */
  def fromStatus(status: GameStatus): GameClosure = status match
    case GameStatus.Checkmate(winner) => Checkmate(winner)
    case GameStatus.Resigned(winner)  => Resigned(winner)
    case GameStatus.Draw(reason)      => Draw(reason)
    case GameStatus.Ongoing(_)        => Cancelled

/** Archive/export snapshot for a completed or closed Game Service session.
  *
  * This is consumed by History through the Game Service archive HTTP endpoint.
  */
final case class GameArchiveSnapshot(
    sessionId: SessionId,
    gameId: GameId,
    mode: SessionMode,
    whiteController: SideController,
    blackController: SideController,
    closure: GameClosure,
    finalState: GameView,
    createdAt: Instant,
    closedAt: Instant
)
