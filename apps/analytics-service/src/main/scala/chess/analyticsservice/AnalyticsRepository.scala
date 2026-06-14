package chess.analyticsservice

trait AnalyticsRepository:
  // ── Run listing ────────────────────────────────────────────────────────────
  def listRuns(): Either[String, List[AnalyticsRunSummary]]

  // ── Latest-run convenience methods ────────────────────────────────────────
  def getLatestLeaderboard(): Either[String, List[LeaderboardRow]]
  def getLatestBotFamilyComparison(): Either[String, List[BotFamilyRow]]
  def getLatestStrategyComparison(): Either[String, List[StrategyRow]]
  def getLatestSearchessAiComparison(): Either[String, List[SearchessAiComparisonRow]]
  def getLatestStockfishComparison(): Either[String, List[StockfishComparisonRow]]
  def getLatestAvgGameLength(): Either[String, List[AvgGameLengthRow]]

  // ── Run-specific methods ───────────────────────────────────────────────────
  def getLeaderboard(runId: String): Either[String, List[LeaderboardRow]]
  def getBotFamilyComparison(runId: String): Either[String, List[BotFamilyRow]]
  def getStrategyComparison(runId: String): Either[String, List[StrategyRow]]
  def getSearchessAiComparison(runId: String): Either[String, List[SearchessAiComparisonRow]]
  def getStockfishComparison(runId: String): Either[String, List[StockfishComparisonRow]]
  def getAvgGameLength(runId: String): Either[String, List[AvgGameLengthRow]]
