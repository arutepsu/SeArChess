package chess.arena.bots.heuristic

import chess.arena.core.BotProfile
import chess.arena.events.BotFamily
import chess.domain.model.{Move, Position}
import chess.domain.rules.GameStateRules
import chess.domain.state.{GameState, GameStateFactory}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CaptureFirstBotSpec extends AnyFlatSpec with Matchers:

  private val profile = BotProfile("capture-first", BotFamily.Heuristic, "capture-first", "none", "none")

  private def pos(s: String): Position =
    Position.fromAlgebraic(s) match
      case Right(p) => p
      case Left(_)  => fail(s"Invalid position: $s")

  /** Advances to a position where White can capture a pawn on d5.
    * Sequence: 1. e2e4  2. d7d5  — White pawn on e4 can take d5.
    */
  private def stateWithCaptureAvailable(): GameState =
    val s0 = GameStateFactory.initial()
    val s1 = GameStateRules.applyMove(s0, Move(pos("e2"), pos("e4"))).fold(e => fail(s"$e"), identity)
    GameStateRules.applyMove(s1, Move(pos("d7"), pos("d5"))).fold(e => fail(s"$e"), identity)

  "CaptureFirstBot" should "select a legal move from the initial position (no captures)" in {
    val bot   = CaptureFirstBot(profile, seed = Some(1L))
    val state = GameStateFactory.initial()
    val move  = bot.selectMove(state)
    GameStateRules.legalMoves(state) should contain(move)
  }

  it should "always select a capture when one is available" in {
    val state    = stateWithCaptureAvailable()
    val captures = GameStateRules.legalMoves(state).filter(m => state.board.pieceAt(m.to).isDefined)
    captures should not be empty

    // Try many seeds — all should yield a capture
    (1L to 20L).foreach { seed =>
      val move = CaptureFirstBot(profile, seed = Some(seed)).selectMove(state)
      captures should contain(move)
    }
  }
