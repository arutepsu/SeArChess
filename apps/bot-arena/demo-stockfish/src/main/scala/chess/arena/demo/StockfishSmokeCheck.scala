package chess.arena.demo

import chess.arena.bots.uci.StockfishBot
import chess.arena.core.MoveUci
import chess.domain.state.GameStateFactory

object StockfishSmokeCheck:

  def main(args: Array[String]): Unit =
    val enginePath = args.headOption
      .orElse(sys.env.get("STOCKFISH_PATH"))
      .getOrElse {
        throw IllegalArgumentException(
          "Provide Stockfish with CLI arg 1 or STOCKFISH_PATH"
        )
      }

    val state = GameStateFactory.initial()
    val move  = StockfishBot.depth1(enginePath).selectMove(state)

    println("=== Stockfish Smoke Check ===")
    println(s"Engine: $enginePath")
    println(s"Move  : ${MoveUci.encode(move)}")
