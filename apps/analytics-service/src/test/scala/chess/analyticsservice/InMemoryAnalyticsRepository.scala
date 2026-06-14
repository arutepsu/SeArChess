package chess.analyticsservice

/** Test double for AnalyticsRepository. Initialised with fixed data or configured to return errors.
  * Run-specific methods return the same data as the latest methods — the InMemory double does not
  * filter by runId, since routing and validation logic is tested at the routes layer.
  */
final class InMemoryAnalyticsRepository(
  runs:          Either[String, List[AnalyticsRunSummary]]          = Right(Nil),
  leaderboard:   Either[String, List[LeaderboardRow]]               = Right(Nil),
  botFamilies:   Either[String, List[BotFamilyRow]]                 = Right(Nil),
  strategies:    Either[String, List[StrategyRow]]                  = Right(Nil),
  searchessAi:   Either[String, List[SearchessAiComparisonRow]]     = Right(Nil),
  stockfish:     Either[String, List[StockfishComparisonRow]]       = Right(Nil),
  avgGameLength: Either[String, List[AvgGameLengthRow]]             = Right(Nil)
) extends AnalyticsRepository:

  // ── Latest-run methods ────────────────────────────────────────────────────
  override def listRuns(): Either[String, List[AnalyticsRunSummary]]               = runs
  override def getLatestLeaderboard(): Either[String, List[LeaderboardRow]]        = leaderboard
  override def getLatestBotFamilyComparison(): Either[String, List[BotFamilyRow]]  = botFamilies
  override def getLatestStrategyComparison(): Either[String, List[StrategyRow]]    = strategies
  override def getLatestSearchessAiComparison(): Either[String, List[SearchessAiComparisonRow]] = searchessAi
  override def getLatestStockfishComparison(): Either[String, List[StockfishComparisonRow]]     = stockfish
  override def getLatestAvgGameLength(): Either[String, List[AvgGameLengthRow]]                 = avgGameLength

  // ── Run-specific methods (ignore runId, return same data as latest) ───────
  override def getLeaderboard(runId: String): Either[String, List[LeaderboardRow]]               = leaderboard
  override def getBotFamilyComparison(runId: String): Either[String, List[BotFamilyRow]]         = botFamilies
  override def getStrategyComparison(runId: String): Either[String, List[StrategyRow]]           = strategies
  override def getSearchessAiComparison(runId: String): Either[String, List[SearchessAiComparisonRow]] = searchessAi
  override def getStockfishComparison(runId: String): Either[String, List[StockfishComparisonRow]]     = stockfish
  override def getAvgGameLength(runId: String): Either[String, List[AvgGameLengthRow]]                 = avgGameLength

object InMemoryAnalyticsRepository:

  val empty: InMemoryAnalyticsRepository = new InMemoryAnalyticsRepository()

  def withError(msg: String): InMemoryAnalyticsRepository = new InMemoryAnalyticsRepository(
    runs          = Left(msg),
    leaderboard   = Left(msg),
    botFamilies   = Left(msg),
    strategies    = Left(msg),
    searchessAi   = Left(msg),
    stockfish     = Left(msg),
    avgGameLength = Left(msg)
  )

  // ── Sample data ───────────────────────────────────────────────────────────

  val sampleRunId:  String = "550e8400-e29b-41d4-a716-446655440000"
  val sampleRunId2: String = "550e8400-e29b-41d4-a716-446655440001"

  val sampleRun: AnalyticsRunSummary =
    AnalyticsRunSummary(sampleRunId, "/data/games.jsonl", "2026-06-13T00:00:00Z")

  val sampleRun2: AnalyticsRunSummary =
    AnalyticsRunSummary(sampleRunId2, "/data/games-v2.jsonl", "2026-06-14T00:00:00Z")

  val sampleLeaderboardRow: LeaderboardRow =
    LeaderboardRow("stockfish-fast", 10.0, 5, 0, 0, 5, 40.0, 1.0)

  val sampleBotFamilyRow: BotFamilyRow =
    BotFamilyRow("stockfish", 10, 5, 2, 3, 8.0, 0.5)

  val sampleStrategyRow: StrategyRow =
    StrategyRow("minimax", 10, 5, 2, 3, 8.0, 0.5)

  val sampleSearchessAiRow: SearchessAiComparisonRow =
    SearchessAiComparisonRow("random-bot", "random", 5, 4, 1, 0, 4.5, 35.0, 0.9)

  val sampleStockfishRow: StockfishComparisonRow =
    StockfishComparisonRow("stockfish-fast", "stockfish", 5, 3, 1, 3.5, 38.0, 0.7)

  val sampleAvgGameLengthRow: AvgGameLengthRow =
    AvgGameLengthRow("stockfish-fast", "random-bot", 5, 42.0, 3200.0)

  def withSampleData: InMemoryAnalyticsRepository = new InMemoryAnalyticsRepository(
    runs          = Right(List(sampleRun)),
    leaderboard   = Right(List(sampleLeaderboardRow)),
    botFamilies   = Right(List(sampleBotFamilyRow)),
    strategies    = Right(List(sampleStrategyRow)),
    searchessAi   = Right(List(sampleSearchessAiRow)),
    stockfish     = Right(List(sampleStockfishRow)),
    avgGameLength = Right(List(sampleAvgGameLengthRow))
  )

  def withTwoRuns: InMemoryAnalyticsRepository = new InMemoryAnalyticsRepository(
    runs          = Right(List(sampleRun2, sampleRun)), // newest first
    leaderboard   = Right(List(sampleLeaderboardRow)),
    botFamilies   = Right(List(sampleBotFamilyRow)),
    strategies    = Right(List(sampleStrategyRow)),
    searchessAi   = Right(List(sampleSearchessAiRow)),
    stockfish     = Right(List(sampleStockfishRow)),
    avgGameLength = Right(List(sampleAvgGameLengthRow))
  )
