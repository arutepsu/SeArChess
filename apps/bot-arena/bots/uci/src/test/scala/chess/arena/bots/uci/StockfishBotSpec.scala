package chess.arena.bots.uci

import chess.arena.events.BotFamily
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class StockfishBotSpec extends AnyFlatSpec with Matchers:

  "StockfishBot profiles" should "use UciEngine family and stable depth metadata" in {
    val depth1 = StockfishBot.depth1("stockfish")
    depth1.profile.botId shouldBe "stockfish-depth-1"
    depth1.profile.family shouldBe BotFamily.UciEngine
    depth1.profile.strategyType shouldBe "depth-1"
    depth1.profile.engineType shouldBe "stockfish"
    depth1.profile.modelVersion shouldBe "none"

    val depth3 = StockfishBot.depth3("stockfish")
    depth3.profile.botId shouldBe "stockfish-depth-3"
    depth3.profile.family shouldBe BotFamily.UciEngine
    depth3.profile.strategyType shouldBe "depth-3"
    depth3.profile.engineType shouldBe "stockfish"
    depth3.profile.modelVersion shouldBe "none"
  }
