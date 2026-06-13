package chess.arena.bots.heuristic

import chess.arena.core.{BotPlayer, BotProfile}
import chess.domain.model.Move
import chess.domain.rules.GameStateRules
import chess.domain.state.GameState

/** Prefers any capture over non-captures; breaks ties randomly. */
final class CaptureFirstBot(val profile: BotProfile, seed: Option[Long] = None) extends BotPlayer:
  private val random: scala.util.Random =
    seed.fold(new scala.util.Random())(s => new scala.util.Random(s))

  def selectMove(state: GameState): Move =
    val moves      = GameStateRules.legalMoves(state).toVector
    val captures   = moves.filter(m => state.board.pieceAt(m.to).isDefined)
    val candidates = if captures.nonEmpty then captures else moves
    candidates(random.nextInt(candidates.length))
