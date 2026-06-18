package chess.tournamentservice

final case class ExportClock(limit: Int, increment: Int)

final case class ExportGame(
    gameId: String,
    tournamentId: String,
    round: Int,
    whiteBotId: String,
    whiteBotName: String,
    whiteBotFamily: Option[String],
    whiteStrategyType: Option[String],
    blackBotId: String,
    blackBotName: String,
    blackBotFamily: Option[String],
    blackStrategyType: Option[String],
    winner: Option[String],
    winnerBotId: Option[String],
    terminationReason: String,
    totalPly: Int,
    moves: String,
    startedAt: Option[String],
    endedAt: Option[String],
    durationMillis: Option[Long],
)

final case class ExportStanding(
    tournamentId: String,
    botId: String,
    botName: String,
    botFamily: Option[String],
    strategyType: Option[String],
    rank: Int,
    points: Double,
    wins: Int,
    draws: Int,
    losses: Int,
    nbGames: Int,
    tieBreak: Double,
)

final case class PublicTournamentExport(
    tournamentId: String,
    format: String,
    clock: ExportClock,
    rated: Boolean,
    startedAt: Option[String],
    finishedAt: Option[String],
    games: List[ExportGame],
    standings: List[ExportStanding],
)

object PublicExportParser:

  def parse(body: String): Either[ImportError, PublicTournamentExport] =
    scala.util.Try(ujson.read(body)).toEither
      .left.map(e => ImportError.ParseError(s"Invalid JSON: ${Option(e.getMessage).getOrElse("unknown")}"))
      .flatMap(parseRoot)

  private def parseRoot(v: ujson.Value): Either[ImportError, PublicTournamentExport] =
    scala.util.Try {
      val obj         = v.obj
      val tournamentId = obj("tournamentId").str
      val format      = obj("format").str
      val clockObj    = obj("clock").obj
      val clock       = ExportClock(clockObj("limit").num.toInt, clockObj("increment").num.toInt)
      val rated       = obj("rated").bool
      val startedAt   = optStr(obj, "startedAt")
      val finishedAt  = optStr(obj, "finishedAt")
      val games       = obj.get("games").map(_.arr.toList.map(parseGame)).getOrElse(Nil)
      val standings   = obj.get("standings").map(_.arr.toList.map(parseStanding)).getOrElse(Nil)
      PublicTournamentExport(tournamentId, format, clock, rated, startedAt, finishedAt, games, standings)
    }.toEither.left.map(e => ImportError.ParseError(s"Malformed export: ${Option(e.getMessage).getOrElse("unknown")}"))

  private def parseGame(v: ujson.Value): ExportGame =
    val obj = v.obj
    ExportGame(
      gameId            = obj("gameId").str,
      tournamentId      = obj("tournamentId").str,
      round             = obj("round").num.toInt,
      whiteBotId        = obj("whiteBotId").str,
      whiteBotName      = obj("whiteBotName").str,
      whiteBotFamily    = optStr(obj, "whiteBotFamily"),
      whiteStrategyType = optStr(obj, "whiteStrategyType"),
      blackBotId        = obj("blackBotId").str,
      blackBotName      = obj("blackBotName").str,
      blackBotFamily    = optStr(obj, "blackBotFamily"),
      blackStrategyType = optStr(obj, "blackStrategyType"),
      winner            = optStr(obj, "winner"),
      winnerBotId       = optStr(obj, "winnerBotId"),
      terminationReason = obj("terminationReason").str,
      totalPly          = obj("totalPly").num.toInt,
      moves             = obj.get("moves").collect { case ujson.Str(s) => s }.getOrElse(""),
      startedAt         = optStr(obj, "startedAt"),
      endedAt           = optStr(obj, "endedAt"),
      durationMillis    = obj.get("durationMillis").collect { case ujson.Num(n) => n.toLong },
    )

  private def parseStanding(v: ujson.Value): ExportStanding =
    val obj = v.obj
    ExportStanding(
      tournamentId = obj("tournamentId").str,
      botId        = obj("botId").str,
      botName      = obj("botName").str,
      botFamily    = optStr(obj, "botFamily"),
      strategyType = optStr(obj, "strategyType"),
      rank         = obj("rank").num.toInt,
      points       = obj("points").num,
      wins         = obj("wins").num.toInt,
      draws        = obj("draws").num.toInt,
      losses       = obj("losses").num.toInt,
      nbGames      = obj("nbGames").num.toInt,
      tieBreak     = obj("tieBreak").num,
    )

  private def optStr(obj: ujson.Obj, key: String): Option[String] =
    obj.obj.get(key).collect { case ujson.Str(s) => s }
