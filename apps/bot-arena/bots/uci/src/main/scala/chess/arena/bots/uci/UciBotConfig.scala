package chess.arena.bots.uci

enum UciSearchMode:
  case Depth, MoveTime

final case class UciBotConfig(
    botId: String,
    enginePath: String,
    engineName: String,
    searchMode: UciSearchMode,
    depth: Option[Int],
    movetimeMillis: Option[Long],
    startupTimeoutMillis: Long,
    moveTimeoutMillis: Long
)

object UciBotConfig:
  def stockfishDepth(
      botId: String,
      enginePath: String,
      depth: Int,
      startupTimeoutMillis: Long = 5000L,
      moveTimeoutMillis: Long = 10000L
  ): UciBotConfig =
    UciBotConfig(
      botId = botId,
      enginePath = enginePath,
      engineName = "Stockfish",
      searchMode = UciSearchMode.Depth,
      depth = Some(depth),
      movetimeMillis = None,
      startupTimeoutMillis = startupTimeoutMillis,
      moveTimeoutMillis = moveTimeoutMillis
    )

  def stockfishMoveTime(
      botId: String,
      enginePath: String,
      movetimeMillis: Long,
      startupTimeoutMillis: Long = 5000L,
      moveTimeoutMillis: Long = 10000L
  ): UciBotConfig =
    UciBotConfig(
      botId = botId,
      enginePath = enginePath,
      engineName = "Stockfish",
      searchMode = UciSearchMode.MoveTime,
      depth = None,
      movetimeMillis = Some(movetimeMillis),
      startupTimeoutMillis = startupTimeoutMillis,
      moveTimeoutMillis = moveTimeoutMillis
    )
