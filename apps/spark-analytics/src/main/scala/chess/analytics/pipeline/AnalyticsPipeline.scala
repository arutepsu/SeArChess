package chess.analytics.pipeline

import org.apache.spark.sql.DataFrame
import chess.analytics.ingestion.SilverEvents
import chess.analytics.quality.DataQualityChecks
import chess.analytics.rating.EloAnalytics

final case class SilverAnalyticsViews(
  gameStarted:  DataFrame,
  movePlayed:   DataFrame,
  gameFinished: DataFrame
)

final case class GoldAnalyticsViews(
  leaderboard:        DataFrame,
  headToHead:         DataFrame,
  terminations:       DataFrame,
  avgLength:          DataFrame,
  familyComparison:   DataFrame,
  strategyComparison: DataFrame,
  colorPerformance:   DataFrame,
  fastestWins:        DataFrame,
  stockfishComp:      DataFrame,
  familyMatchups:     DataFrame,
  searchessAiComp:    DataFrame,
  eloRatings:         DataFrame
)

final case class AnalyticsBatchResult(
  silver:      SilverAnalyticsViews,
  gold:        GoldAnalyticsViews,
  dataQuality: DataFrame
)

object AnalyticsPipeline {

  def buildSilver(events: DataFrame): SilverAnalyticsViews =
    SilverAnalyticsViews(
      gameStarted  = SilverEvents.gameStarted(events),
      movePlayed   = SilverEvents.movePlayed(events),
      gameFinished = SilverEvents.gameFinished(events)
    )

  def buildGold(gameFinished: DataFrame): GoldAnalyticsViews =
    GoldAnalyticsViews(
      leaderboard        = GameAnalytics.computeLeaderboard(gameFinished),
      headToHead         = GameAnalytics.computeHeadToHead(gameFinished),
      terminations       = GameAnalytics.computeTerminations(gameFinished),
      avgLength          = GameAnalytics.computeAvgGameLength(gameFinished),
      familyComparison   = GameAnalytics.computeBotFamilyComparison(gameFinished),
      strategyComparison = GameAnalytics.computeStrategyComparison(gameFinished),
      colorPerformance   = GameAnalytics.computeColorPerformance(gameFinished),
      fastestWins        = GameAnalytics.computeFastestWins(gameFinished),
      stockfishComp      = GameAnalytics.computeStockfishComparison(gameFinished),
      familyMatchups     = GameAnalytics.computeFamilyMatchups(gameFinished),
      searchessAiComp    = GameAnalytics.computeSearchessAiComparison(gameFinished),
      eloRatings         = EloAnalytics.computeRatings(gameFinished)
    )

  def buildDataQuality(gameFinished: DataFrame): DataFrame =
    DataQualityChecks.gameFinishedChecks(gameFinished)

  def buildBatchResult(events: DataFrame): AnalyticsBatchResult = {
    val silver = buildSilver(events)
    AnalyticsBatchResult(
      silver      = silver,
      gold        = buildGold(silver.gameFinished),
      dataQuality = buildDataQuality(silver.gameFinished)
    )
  }
}
