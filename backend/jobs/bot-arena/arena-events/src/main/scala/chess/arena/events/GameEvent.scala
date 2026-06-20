package chess.arena.events

object GameEvent:
  val CurrentSchemaVersion: String = "1"

sealed trait GameEvent:
  def schemaVersion: String
  def eventType: String
  def eventId: String
  def timestamp: String
  def tournamentId: String
  def gameId: String

final case class GameStarted(
    eventId: String,
    timestamp: String,
    tournamentId: String,
    gameId: String,
    whiteBotId: String,
    blackBotId: String,
    whiteBotFamily: String,
    blackBotFamily: String,
    whiteStrategyType: String,
    blackStrategyType: String,
    whiteEngineType: String,
    blackEngineType: String,
    whiteModelVersion: String,
    blackModelVersion: String
) extends GameEvent:
  val schemaVersion: String = GameEvent.CurrentSchemaVersion
  val eventType: String     = "GameStarted"

final case class MovePlayed(
    eventId: String,
    timestamp: String,
    tournamentId: String,
    gameId: String,
    botId: String,
    moveUci: String,
    plyNumber: Int,
    durationMillis: Long
) extends GameEvent:
  val schemaVersion: String = GameEvent.CurrentSchemaVersion
  val eventType: String     = "MovePlayed"

final case class GameFinished(
    eventId: String,
    timestamp: String,
    tournamentId: String,
    gameId: String,
    whiteBotId: String,
    blackBotId: String,
    winnerBotId: Option[String],
    loserBotId: Option[String],
    result: String,
    whiteBotFamily: String,
    blackBotFamily: String,
    whiteStrategyType: String,
    blackStrategyType: String,
    whiteEngineType: String,
    blackEngineType: String,
    whiteModelVersion: String,
    blackModelVersion: String,
    totalMoves: Int,
    totalPly: Int,
    durationMillis: Long,
    terminationReason: String
) extends GameEvent:
  val schemaVersion: String = GameEvent.CurrentSchemaVersion
  val eventType: String     = "GameFinished"
