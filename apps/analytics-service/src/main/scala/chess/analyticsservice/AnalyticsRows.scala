package chess.analyticsservice

final case class AnalyticsRunSummary(runId: String, sourcePath: String, createdAt: String)

final case class LeaderboardRow(
  botId: String, totalScore: Double, wins: Long, draws: Long, losses: Long,
  gamesPlayed: Long, avgPly: Double, winRate: Double
)

final case class BotFamilyRow(
  family: String, games: Long, wins: Long, losses: Long, draws: Long,
  totalScore: Double, winRate: Double
)

final case class StrategyRow(
  strategyType: String, games: Long, wins: Long, losses: Long, draws: Long,
  totalScore: Double, winRate: Double
)

final case class SearchessAiComparisonRow(
  opponentBotId: String, opponentFamily: String, games: Long, searchessAiWins: Long,
  draws: Long, losses: Long, score: Double, avgGameLength: Double, winRate: Double
)

final case class StockfishComparisonRow(
  botId: String, strategyType: String, games: Long, wins: Long, draws: Long,
  totalScore: Double, avgGameLength: Double, winRate: Double
)

final case class AvgGameLengthRow(
  whiteBotId: String, blackBotId: String, gamesPlayed: Long,
  avgTotalPly: Double, avgDurationMs: Double
)

final case class EloRatingsRow(
  botId: String, rating: Double, ratingChange: Double, gamesPlayed: Long,
  wins: Long, draws: Long, losses: Long, averageOpponentRating: Double,
  lastGameTimestamp: String
)

final case class TerminationReasonRow(terminationReason: String, count: Long)

final case class ColorPerformanceRow(
  botId: String, gamesAsWhite: Long, whiteWins: Long, whiteScore: Double,
  gamesAsBlack: Long, blackWins: Long, blackScore: Double
)

final case class FastestWinRow(
  winnerBotId: String, decisiveGames: Long, avgWinPly: Double,
  minWinPly: Long, avgWinDurationMs: Double
)
