package chess.arena.bots.heuristic

import chess.arena.core.BotProfile
import chess.arena.events.BotFamily
import chess.domain.rules.GameStateRules
import chess.domain.state.GameStateFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RandomBotSpec extends AnyFlatSpec with Matchers:

  private val profile = BotProfile("random-bot", BotFamily.Heuristic, "random", "none", "none")

  "RandomBot" should "select a legal move from the initial position" in {
    val bot   = RandomBot(profile, seed = Some(1L))
    val state = GameStateFactory.initial()
    val move  = bot.selectMove(state)
    GameStateRules.legalMoves(state) should contain(move)
  }

  it should "select the same move given the same seed" in {
    val state = GameStateFactory.initial()
    val move1 = RandomBot(profile, seed = Some(42L)).selectMove(state)
    val move2 = RandomBot(profile, seed = Some(42L)).selectMove(state)
    move1 shouldBe move2
  }

  it should "select different moves across many seeds" in {
    val state = GameStateFactory.initial()
    val moves = (1L to 20L).map(s => RandomBot(profile, seed = Some(s)).selectMove(state)).toSet
    moves.size should be > 1
  }
