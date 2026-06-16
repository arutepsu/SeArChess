package chess.adapter.event.kafka

object KafkaTopics:
  val GameEvents: String     = "searchess.game.events.v1"
  val AiCommands: String     = "searchess.ai.commands.v1"
  val AiEvents: String       = "searchess.ai.events.v1"
  val LichessEvents: String  = "searchess.lichess.events.v1"

  val GameEventsRetry10s: String = "searchess.game.events.retry.10s.v1"
  val GameEventsRetry1m: String  = "searchess.game.events.retry.1m.v1"
  val GameEventsRetry10m: String = "searchess.game.events.retry.10m.v1"
  val GameEventsDlq: String      = "searchess.game.events.dlq.v1"

  val CoreTopics: Seq[String] =
    Seq(GameEvents, AiCommands, AiEvents, LichessEvents)

  val GameRetryTopics: Seq[String] =
    Seq(GameEventsRetry10s, GameEventsRetry1m, GameEventsRetry10m)
