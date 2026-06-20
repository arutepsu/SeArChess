package chess.analyticsservice

/** Test double for AnalyticsRepository. Initialised with fixed data or configured to return errors.
  * Run-specific methods return the same data as the latest methods — the InMemory double does not
  * filter by runId, since routing and validation logic is tested at the routes layer.
  */
final class InMemoryAnalyticsRepository(
  runs:             Either[String, List[AnalyticsRunSummary]]      = Right(Nil),
  leaderboard:      Either[String, List[LeaderboardRow]]           = Right(Nil),
  botFamilies:      Either[String, List[BotFamilyRow]]             = Right(Nil),
  strategies:       Either[String, List[StrategyRow]]              = Right(Nil),
  searchessAi:      Either[String, List[SearchessAiComparisonRow]] = Right(Nil),
  stockfish:        Either[String, List[StockfishComparisonRow]]   = Right(Nil),
  avgGameLength:    Either[String, List[AvgGameLengthRow]]         = Right(Nil),
  eloRatings:       Either[String, List[EloRatingsRow]]            = Right(Nil),
  terminations:     Either[String, List[TerminationReasonRow]]     = Right(Nil),
  colorPerf:        Either[String, List[ColorPerformanceRow]]      = Right(Nil),
  fastestWins:      Either[String, List[FastestWinRow]]            = Right(Nil),
  liveGameResults:  Either[String, List[LiveGameResultRow]]        = Right(Nil),
  headToHead:       Either[String, List[HeadToHeadRow]]            = Right(Nil),
  familyMatchups:   Either[String, List[FamilyMatchupRow]]         = Right(Nil),
  gameResults:      Either[String, List[GameResultRow]]            = Right(Nil)
) extends AnalyticsRepository:

  // ── Latest-run methods ────────────────────────────────────────────────────
  override def listRuns(): Either[String, List[AnalyticsRunSummary]]               = runs
  override def getLatestLeaderboard(): Either[String, List[LeaderboardRow]]        = leaderboard
  override def getLatestBotFamilyComparison(): Either[String, List[BotFamilyRow]]  = botFamilies
  override def getLatestStrategyComparison(): Either[String, List[StrategyRow]]    = strategies
  override def getLatestSearchessAiComparison(): Either[String, List[SearchessAiComparisonRow]] = searchessAi
  override def getLatestStockfishComparison(): Either[String, List[StockfishComparisonRow]]     = stockfish
  override def getLatestAvgGameLength(): Either[String, List[AvgGameLengthRow]]                 = avgGameLength
  override def getEloRatings(): Either[String, List[EloRatingsRow]]                             = eloRatings
  override def getTerminations(): Either[String, List[TerminationReasonRow]]                     = terminations
  override def getColorPerformance(): Either[String, List[ColorPerformanceRow]]                  = colorPerf
  override def getFastestWins(): Either[String, List[FastestWinRow]]                             = fastestWins

  // ── Live streaming results ─────────────────────────────────────────────────
  override def getLiveGameResults(limit: Int): Either[String, List[LiveGameResultRow]] = liveGameResults

  // ── Run-specific methods (ignore runId, return same data as latest) ───────
  override def getHeadToHead(runId: String): Either[String, List[HeadToHeadRow]]           = headToHead
  override def getFamilyMatchups(runId: String): Either[String, List[FamilyMatchupRow]]    = familyMatchups
  override def getGameResults(runId: String): Either[String, List[GameResultRow]]          = gameResults
  override def getLeaderboard(runId: String): Either[String, List[LeaderboardRow]]               = leaderboard
  override def getBotFamilyComparison(runId: String): Either[String, List[BotFamilyRow]]         = botFamilies
  override def getStrategyComparison(runId: String): Either[String, List[StrategyRow]]           = strategies
  override def getSearchessAiComparison(runId: String): Either[String, List[SearchessAiComparisonRow]] = searchessAi
  override def getStockfishComparison(runId: String): Either[String, List[StockfishComparisonRow]]     = stockfish
  override def getAvgGameLength(runId: String): Either[String, List[AvgGameLengthRow]]                 = avgGameLength
  override def getEloRatings(runId: String): Either[String, List[EloRatingsRow]]                       = eloRatings
  override def getTerminations(runId: String): Either[String, List[TerminationReasonRow]]               = terminations
  override def getColorPerformance(runId: String): Either[String, List[ColorPerformanceRow]]            = colorPerf
  override def getFastestWins(runId: String): Either[String, List[FastestWinRow]]                       = fastestWins

object InMemoryAnalyticsRepository:

  val empty: InMemoryAnalyticsRepository = new InMemoryAnalyticsRepository()

  def withError(msg: String): InMemoryAnalyticsRepository = new InMemoryAnalyticsRepository(
    runs            = Left(msg),
    leaderboard     = Left(msg),
    botFamilies     = Left(msg),
    strategies      = Left(msg),
    searchessAi     = Left(msg),
    stockfish       = Left(msg),
    avgGameLength   = Left(msg),
    eloRatings      = Left(msg),
    terminations    = Left(msg),
    colorPerf       = Left(msg),
    fastestWins     = Left(msg),
    liveGameResults = Left(msg),
    headToHead      = Left(msg),
    familyMatchups  = Left(msg),
    gameResults     = Left(msg)
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

  val sampleEloRatingsRow: EloRatingsRow =
    EloRatingsRow("stockfish-fast", 1055.4, 55.4, 5, 4, 1, 0, 998.2, "2026-06-13T00:01:00Z")

  val sampleTerminationReasonRow: TerminationReasonRow =
    TerminationReasonRow("checkmate", 7)

  val sampleColorPerformanceRow: ColorPerformanceRow =
    ColorPerformanceRow("stockfish-fast", 3, 2, 2.5, 2, 2, 2.0)

  val sampleFastestWinRow: FastestWinRow =
    FastestWinRow("stockfish-fast", 4, 28.5, 18, 2100.0)

  val sampleLiveGameResultRow: LiveGameResultRow =
    LiveGameResultRow(
      eventId       = "550e8400-e29b-41d4-a716-446655440abc",
      aggregateId   = "game-001",
      sessionId     = Some("sess-001"),
      occurredAt    = "2026-06-17T10:00:00Z",
      result        = "Checkmate",
      winner        = Some("White"),
      drawReason    = None,
      correlationId = Some("corr-001"),
      ingestedAt    = "2026-06-17T10:00:01Z"
    )

  val sampleHeadToHeadRow: HeadToHeadRow =
    HeadToHeadRow("alpha", "beta", 2L, 1L, 0L, 1L, 1.5, 0.5)

  val sampleFamilyMatchupRow: FamilyMatchupRow =
    FamilyMatchupRow("heuristic", "heuristic", 3L, 1.5, 1.5, 1L)

  val sampleGameResultRow: GameResultRow =
    GameResultRow(
      gameId            = "game-01",
      tournamentId      = "pub-test-01",
      whiteBotId        = "alpha",
      blackBotId        = "beta",
      result            = "white",
      winnerBotId       = Some("alpha"),
      loserBotId        = Some("beta"),
      terminationReason = "checkmate",
      totalPly          = 5L,
      durationMillis    = 45000L,
      endedAt           = "2025-01-01T10:02:00Z"
    )

  val sampleDrawGameResultRow: GameResultRow =
    GameResultRow(
      gameId            = "game-03",
      tournamentId      = "pub-test-01",
      whiteBotId        = "alpha",
      blackBotId        = "beta",
      result            = "draw",
      winnerBotId       = None,
      loserBotId        = None,
      terminationReason = "stalemate",
      totalPly          = 8L,
      durationMillis    = 70000L,
      endedAt           = "2025-01-01T10:06:10Z"
    )

  def withSampleData: InMemoryAnalyticsRepository = new InMemoryAnalyticsRepository(
    runs            = Right(List(sampleRun)),
    leaderboard     = Right(List(sampleLeaderboardRow)),
    botFamilies     = Right(List(sampleBotFamilyRow)),
    strategies      = Right(List(sampleStrategyRow)),
    searchessAi     = Right(List(sampleSearchessAiRow)),
    stockfish       = Right(List(sampleStockfishRow)),
    avgGameLength   = Right(List(sampleAvgGameLengthRow)),
    eloRatings      = Right(List(sampleEloRatingsRow)),
    terminations    = Right(List(sampleTerminationReasonRow)),
    colorPerf       = Right(List(sampleColorPerformanceRow)),
    fastestWins     = Right(List(sampleFastestWinRow)),
    liveGameResults = Right(List(sampleLiveGameResultRow)),
    headToHead      = Right(List(sampleHeadToHeadRow)),
    familyMatchups  = Right(List(sampleFamilyMatchupRow)),
    gameResults     = Right(List(sampleGameResultRow, sampleDrawGameResultRow))
  )

  def withTwoRuns: InMemoryAnalyticsRepository = new InMemoryAnalyticsRepository(
    runs            = Right(List(sampleRun2, sampleRun)), // newest first
    leaderboard     = Right(List(sampleLeaderboardRow)),
    botFamilies     = Right(List(sampleBotFamilyRow)),
    strategies      = Right(List(sampleStrategyRow)),
    searchessAi     = Right(List(sampleSearchessAiRow)),
    stockfish       = Right(List(sampleStockfishRow)),
    avgGameLength   = Right(List(sampleAvgGameLengthRow)),
    eloRatings      = Right(List(sampleEloRatingsRow)),
    terminations    = Right(List(sampleTerminationReasonRow)),
    colorPerf       = Right(List(sampleColorPerformanceRow)),
    fastestWins     = Right(List(sampleFastestWinRow)),
    liveGameResults = Right(List(sampleLiveGameResultRow)),
    headToHead      = Right(List(sampleHeadToHeadRow)),
    familyMatchups  = Right(List(sampleFamilyMatchupRow)),
    gameResults     = Right(List(sampleGameResultRow))
  )
