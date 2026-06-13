package chess.arena.bots.ai

final case class AiServiceBotConfig(
    baseUrl: String,
    endpointPath: String,
    timeoutMillis: Long,
    botId: String,
    modelVersion: String,
    engineId: Option[String],
    gameIdPrefix: String
)

object AiServiceBotConfig:
  def fromEnv(): AiServiceBotConfig =
    AiServiceBotConfig(
      baseUrl       = sys.env.getOrElse("SEARCHESS_AI_BASE_URL", "http://localhost:8765"),
      endpointPath  = "/v1/move-suggestions",
      timeoutMillis = sys.env.get("SEARCHESS_AI_TIMEOUT_MILLIS").flatMap(_.toLongOption).getOrElse(5000L),
      botId         = "searchess-ai-v1",
      modelVersion  = "v1",
      engineId      = None,
      gameIdPrefix  = "bot-arena"
    )
