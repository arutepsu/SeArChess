package chess.lichessbridge

final case class BotGameSnapshot(
    gameId: String,
    fen: String,
    moves: String,
    botColor: String,
    wtime: Option[Int],
    btime: Option[Int],
    status: String,
    lastMove: Option[String],
    lastUpdated: Long
)

object BotGameSnapshot:
  def toJson(s: BotGameSnapshot): ujson.Value =
    val obj = ujson.Obj(
      "gameId"      -> s.gameId,
      "fen"         -> s.fen,
      "moves"       -> s.moves,
      "botColor"    -> s.botColor,
      "status"      -> s.status,
      "lastUpdated" -> s.lastUpdated
    )
    s.wtime.foreach(t  => obj("wtime")    = t)
    s.btime.foreach(t  => obj("btime")    = t)
    s.lastMove.foreach(m => obj("lastMove") = m)
    obj
