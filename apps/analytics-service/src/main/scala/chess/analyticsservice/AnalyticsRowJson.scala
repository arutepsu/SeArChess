package chess.analyticsservice

import ujson.{Obj, Value}

object AnalyticsRowJson:

  def runSummary(r: AnalyticsRunSummary): Value =
    Obj("runId" -> r.runId, "sourcePath" -> r.sourcePath, "createdAt" -> r.createdAt)

  def leaderboard(r: LeaderboardRow): Value =
    Obj(
      "botId"       -> r.botId,
      "totalScore"  -> r.totalScore,
      "wins"        -> r.wins.toDouble,
      "draws"       -> r.draws.toDouble,
      "losses"      -> r.losses.toDouble,
      "gamesPlayed" -> r.gamesPlayed.toDouble,
      "avgPly"      -> r.avgPly,
      "winRate"     -> r.winRate
    )

  def botFamily(r: BotFamilyRow): Value =
    Obj(
      "family"     -> r.family,
      "games"      -> r.games.toDouble,
      "wins"       -> r.wins.toDouble,
      "losses"     -> r.losses.toDouble,
      "draws"      -> r.draws.toDouble,
      "totalScore" -> r.totalScore,
      "winRate"    -> r.winRate
    )

  def strategy(r: StrategyRow): Value =
    Obj(
      "strategyType" -> r.strategyType,
      "games"        -> r.games.toDouble,
      "wins"         -> r.wins.toDouble,
      "losses"       -> r.losses.toDouble,
      "draws"        -> r.draws.toDouble,
      "totalScore"   -> r.totalScore,
      "winRate"      -> r.winRate
    )

  def searchessAi(r: SearchessAiComparisonRow): Value =
    Obj(
      "opponentBotId"   -> r.opponentBotId,
      "opponentFamily"  -> r.opponentFamily,
      "games"           -> r.games.toDouble,
      "searchessAiWins" -> r.searchessAiWins.toDouble,
      "draws"           -> r.draws.toDouble,
      "losses"          -> r.losses.toDouble,
      "score"           -> r.score,
      "avgGameLength"   -> r.avgGameLength,
      "winRate"         -> r.winRate
    )

  def stockfish(r: StockfishComparisonRow): Value =
    Obj(
      "botId"         -> r.botId,
      "strategyType"  -> r.strategyType,
      "games"         -> r.games.toDouble,
      "wins"          -> r.wins.toDouble,
      "draws"         -> r.draws.toDouble,
      "totalScore"    -> r.totalScore,
      "avgGameLength" -> r.avgGameLength,
      "winRate"       -> r.winRate
    )

  def avgGameLength(r: AvgGameLengthRow): Value =
    Obj(
      "whiteBotId"    -> r.whiteBotId,
      "blackBotId"    -> r.blackBotId,
      "gamesPlayed"   -> r.gamesPlayed.toDouble,
      "avgTotalPly"   -> r.avgTotalPly,
      "avgDurationMs" -> r.avgDurationMs
    )

  def eloRatings(r: EloRatingsRow): Value =
    Obj(
      "botId"                 -> r.botId,
      "rating"                -> r.rating,
      "ratingChange"          -> r.ratingChange,
      "gamesPlayed"           -> r.gamesPlayed.toDouble,
      "wins"                  -> r.wins.toDouble,
      "draws"                 -> r.draws.toDouble,
      "losses"                -> r.losses.toDouble,
      "averageOpponentRating" -> r.averageOpponentRating,
      "lastGameTimestamp"     -> r.lastGameTimestamp
    )

  def terminationReason(r: TerminationReasonRow): Value =
    Obj(
      "terminationReason" -> r.terminationReason,
      "count"             -> r.count.toDouble
    )

  def colorPerformance(r: ColorPerformanceRow): Value =
    Obj(
      "botId"        -> r.botId,
      "gamesAsWhite" -> r.gamesAsWhite.toDouble,
      "whiteWins"    -> r.whiteWins.toDouble,
      "whiteScore"   -> r.whiteScore,
      "gamesAsBlack" -> r.gamesAsBlack.toDouble,
      "blackWins"    -> r.blackWins.toDouble,
      "blackScore"   -> r.blackScore
    )

  def fastestWin(r: FastestWinRow): Value =
    Obj(
      "winnerBotId"      -> r.winnerBotId,
      "decisiveGames"    -> r.decisiveGames.toDouble,
      "avgWinPly"        -> r.avgWinPly,
      "minWinPly"        -> r.minWinPly.toDouble,
      "avgWinDurationMs" -> r.avgWinDurationMs
    )

  def liveGameResult(r: LiveGameResultRow): Value =
    Obj(
      "eventId"       -> r.eventId,
      "aggregateId"   -> r.aggregateId,
      "sessionId"     -> r.sessionId.map(ujson.Str(_)).getOrElse(ujson.Null),
      "occurredAt"    -> r.occurredAt,
      "result"        -> r.result,
      "winner"        -> r.winner.map(ujson.Str(_)).getOrElse(ujson.Null),
      "drawReason"    -> r.drawReason.map(ujson.Str(_)).getOrElse(ujson.Null),
      "correlationId" -> r.correlationId.map(ujson.Str(_)).getOrElse(ujson.Null),
      "ingestedAt"    -> r.ingestedAt
    )
