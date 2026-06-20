package chess.arena.core

import chess.arena.events.BotFamily
import chess.domain.model.Move
import chess.domain.rules.GameStateRules
import chess.domain.state.GameState
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RoundRobinSchedulerSpec extends AnyFlatSpec with Matchers:

  private def mkBot(id: String): BotPlayer = new BotPlayer:
    val profile: BotProfile = BotProfile(id, BotFamily.Heuristic, "test", "none", "none")
    def selectMove(state: GameState): Move =
      GameStateRules.legalMoves(state).minBy(m => s"${m.from}${m.to}")

  "RoundRobinScheduler.schedule" should "produce n*(n-1) matchups for n bots and default repetitions" in {
    val bots = List(mkBot("A"), mkBot("B"), mkBot("C"))
    RoundRobinScheduler.schedule("t", bots).length shouldBe 6
  }

  it should "produce n*(n-1)*r matchups for r repetitions" in {
    val bots = List(mkBot("A"), mkBot("B"), mkBot("C"), mkBot("D"))
    RoundRobinScheduler.schedule("t", bots, repetitions = 3).length shouldBe 4 * 3 * 3
  }

  it should "exclude self-play (no bot plays itself)" in {
    val bots = List(mkBot("A"), mkBot("B"), mkBot("C"))
    RoundRobinScheduler.schedule("t", bots).foreach { m =>
      m.white.profile.botId should not equal m.black.profile.botId
    }
  }

  it should "include each ordered (white, black) pair exactly once per repetition" in {
    val bots = List(mkBot("A"), mkBot("B"), mkBot("C"))
    val matchups = RoundRobinScheduler.schedule("t", bots)
    val pairs = matchups.map(m => (m.white.profile.botId, m.black.profile.botId))
    pairs.distinct.length shouldBe 6
    pairs should contain(("A", "B"))
    pairs should contain(("B", "A"))
    pairs should contain(("A", "C"))
    pairs should contain(("C", "A"))
    pairs should contain(("B", "C"))
    pairs should contain(("C", "B"))
  }

  it should "double the matchup count when repetitions=2" in {
    val bots = List(mkBot("A"), mkBot("B"), mkBot("C"))
    val one = RoundRobinScheduler.schedule("t", bots, repetitions = 1).length
    val two = RoundRobinScheduler.schedule("t", bots, repetitions = 2).length
    two shouldBe one * 2
  }

  it should "assign sequential game IDs with the tournamentId prefix" in {
    val bots = List(mkBot("A"), mkBot("B"))
    val matchups = RoundRobinScheduler.schedule("tour-42", bots)
    matchups.map(_.gameId) shouldBe List("tour-42-game-1", "tour-42-game-2")
  }

  it should "assign unique game IDs across all matchups" in {
    val bots = List(mkBot("A"), mkBot("B"), mkBot("C"))
    val matchups = RoundRobinScheduler.schedule("t", bots)
    matchups.map(_.gameId).distinct.length shouldBe matchups.length
  }
