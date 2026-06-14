package chess.tournamentservice

import chess.arena.bots.ai.{AiServiceBotConfig, HttpSearchessAiClient, SearchessAiBot}
import chess.arena.bots.heuristic.{CaptureFirstBot, MaterialGreedyBot, RandomBot}
import chess.arena.bots.uci.StockfishBot
import chess.arena.core.{BotPlayer, BotProfile}
import chess.arena.events.BotFamily
import java.nio.file.{Files, Paths}

trait BotRegistry:
  def listBots(): List[BotDescriptor]
  def createBot(botId: String, seed: Option[Long]): Either[String, BotPlayer]

final class DefaultBotRegistry(config: TournamentServiceConfig) extends BotRegistry:

  private final case class BotEntry(
      descriptor: BotDescriptor,
      create: Option[Long] => Either[String, BotPlayer]
  )

  def listBots(): List[BotDescriptor] =
    entries.map(_.descriptor)

  def createBot(botId: String, seed: Option[Long]): Either[String, BotPlayer] =
    entries.find(_.descriptor.botId == botId) match
      case None =>
        Left(s"Unknown botId: $botId")
      case Some(entry) if !entry.descriptor.available =>
        Left(entry.descriptor.unavailableReason.getOrElse(s"Bot is unavailable: $botId"))
      case Some(entry) =>
        entry.create(seed)

  private def entries: List[BotEntry] =
    heuristicEntries ++ stockfishEntries ++ aiEntries

  private def heuristicEntries: List[BotEntry] =
    List(
      BotEntry(
        available("random-bot", "Random Bot", BotFamily.Heuristic, "random", "none", "none"),
        seed => Right(RandomBot(profile("random-bot", BotFamily.Heuristic, "random", "none", "none"), seed))
      ),
      BotEntry(
        available("capture-first", "Capture First", BotFamily.Heuristic, "capture-first", "none", "none"),
        seed => Right(CaptureFirstBot(profile("capture-first", BotFamily.Heuristic, "capture-first", "none", "none"), seed))
      ),
      BotEntry(
        available("material-greedy", "Material Greedy", BotFamily.Heuristic, "material-greedy", "none", "none"),
        seed => Right(MaterialGreedyBot(profile("material-greedy", BotFamily.Heuristic, "material-greedy", "none", "none"), seed))
      )
    )

  private def stockfishEntries: List[BotEntry] =
    val availability = stockfishAvailability
    List(
      stockfishEntry("stockfish-depth-1", "Stockfish Depth 1", "depth-1", availability, StockfishBot.depth1),
      stockfishEntry("stockfish-depth-3", "Stockfish Depth 3", "depth-3", availability, StockfishBot.depth3),
      stockfishEntry("stockfish-fast", "Stockfish Fast", "movetime-100", availability, StockfishBot.fast),
      stockfishEntry("stockfish-slow", "Stockfish Slow", "movetime-1000", availability, StockfishBot.slow)
    )

  private def stockfishEntry(
      botId: String,
      displayName: String,
      strategyType: String,
      availability: Either[String, String],
      factory: String => BotPlayer
  ): BotEntry =
    availability match
      case Right(path) =>
        BotEntry(available(botId, displayName, BotFamily.UciEngine, strategyType, "stockfish", "none"), _ => Right(factory(path)))
      case Left(reason) =>
        BotEntry(unavailable(botId, displayName, BotFamily.UciEngine, strategyType, "stockfish", "none", reason), _ => Left(reason))

  private def aiEntries: List[BotEntry] =
    val botId = "searchess-ai-v1"
    config.searchessAiBaseUrl match
      case Some(baseUrl) =>
        List(
          BotEntry(
            available(botId, "Searchess AI v1", BotFamily.AiService, "searchess-ai", "none", "v1"),
            _ => {
              val botConfig = AiServiceBotConfig(
                baseUrl       = baseUrl,
                endpointPath  = "/v1/move-suggestions",
                timeoutMillis = 5000L,
                botId         = botId,
                modelVersion  = "v1",
                engineId      = None,
                gameIdPrefix  = "tournament-service"
              )
              Right(SearchessAiBot(botConfig, HttpSearchessAiClient(botConfig)))
            }
          )
        )
      case None =>
        List(
          BotEntry(
            unavailable(
              botId,
              "Searchess AI v1",
              BotFamily.AiService,
              "searchess-ai",
              "none",
              "v1",
              "SEARCHESS_AI_BASE_URL is not configured"
            ),
            _ => Left("SEARCHESS_AI_BASE_URL is not configured")
          )
        )

  private def stockfishAvailability: Either[String, String] =
    config.stockfishPath match
      case Some(path) if Files.isRegularFile(Paths.get(path)) => Right(path)
      case _ => Left("STOCKFISH_PATH is not configured or file does not exist")

  private def profile(
      botId: String,
      family: BotFamily,
      strategyType: String,
      engineType: String,
      modelVersion: String
  ): BotProfile =
    BotProfile(botId, family, strategyType, engineType, modelVersion)

  private def available(
      botId: String,
      displayName: String,
      family: BotFamily,
      strategyType: String,
      engineType: String,
      modelVersion: String
  ): BotDescriptor =
    BotDescriptor(botId, displayName, family.toJsonString, strategyType, engineType, modelVersion, available = true, None)

  private def unavailable(
      botId: String,
      displayName: String,
      family: BotFamily,
      strategyType: String,
      engineType: String,
      modelVersion: String,
      reason: String
  ): BotDescriptor =
    BotDescriptor(botId, displayName, family.toJsonString, strategyType, engineType, modelVersion, available = false, Some(reason))
