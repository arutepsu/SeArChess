package chess.arena.bots.heuristic

import chess.arena.core.{BotPlayer, BotProfile}
import chess.domain.model.Move
import chess.domain.rules.GameStateRules
import chess.domain.state.GameState

/** Picks the capture with the highest material value; breaks ties and non-capture selection randomly. */
final class MaterialGreedyBot(val profile: BotProfile, seed: Option[Long] = None) extends BotPlayer:
  private val random: scala.util.Random =
    seed.fold(new scala.util.Random())(s => new scala.util.Random(s))

  def selectMove(state: GameState): Move =
    val moves    = GameStateRules.legalMoves(state).toVector
    val captures = moves.filter(m => state.board.pieceAt(m.to).isDefined)
    if captures.isEmpty then
      moves(random.nextInt(moves.length))
    else
      val valued   = captures.map(m => (m, state.board.pieceAt(m.to).fold(0)(p => PieceValue.of(p.pieceType))))
      val maxValue = valued.map(_._2).max
      val best     = valued.collect { case (m, v) if v == maxValue => m }
      best(random.nextInt(best.length))
