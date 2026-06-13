package chess.arena.bots.heuristic

import chess.arena.core.{BotPlayer, BotProfile}
import chess.domain.model.Move
import chess.domain.rules.GameStateRules
import chess.domain.state.GameState

/** Selects a uniformly random legal move. */
final class RandomBot(val profile: BotProfile, seed: Option[Long] = None) extends BotPlayer:
  private val random: scala.util.Random =
    seed.fold(new scala.util.Random())(s => new scala.util.Random(s))

  def selectMove(state: GameState): Move =
    val moves = GameStateRules.legalMoves(state).toVector
    moves(random.nextInt(moves.length))
