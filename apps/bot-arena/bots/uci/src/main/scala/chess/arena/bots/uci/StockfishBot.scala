package chess.arena.bots.uci

import chess.arena.core.{BotPlayer, BotProfile}
import chess.arena.events.BotFamily
import chess.domain.model.Move
import chess.domain.state.GameState

final class StockfishBot(
    config: UciBotConfig,
    engine: UciEngine = UciEngine.processBacked
) extends BotPlayer:

  val profile: BotProfile =
    BotProfile(
      botId = config.botId,
      family = BotFamily.UciEngine,
      strategyType = strategyType(config),
      engineType = "stockfish",
      modelVersion = "none"
    )

  def selectMove(state: GameState): Move =
    val result =
      for
        fen      <- UciPosition.fen(state)
        bestMove <- engine.bestMove(fen, config)
        move     <- bestMove.toLegalDomainMove(state)
      yield move

    result.fold(message => throw IllegalStateException(message), identity)

  private def strategyType(config: UciBotConfig): String =
    config.searchMode match
      case UciSearchMode.Depth =>
        config.depth.fold("depth")(depth => s"depth-$depth")
      case UciSearchMode.MoveTime =>
        config.movetimeMillis.fold("movetime")(millis => s"movetime-$millis")

object StockfishBot:
  def depth1(enginePath: String): StockfishBot =
    StockfishBot(UciBotConfig.stockfishDepth("stockfish-depth-1", enginePath, 1))

  def depth3(enginePath: String): StockfishBot =
    StockfishBot(UciBotConfig.stockfishDepth("stockfish-depth-3", enginePath, 3))

  def fast(enginePath: String): StockfishBot =
    StockfishBot(UciBotConfig.stockfishMoveTime("stockfish-fast", enginePath, 100L))

  def slow(enginePath: String): StockfishBot =
    StockfishBot(UciBotConfig.stockfishMoveTime("stockfish-slow", enginePath, 1000L))
