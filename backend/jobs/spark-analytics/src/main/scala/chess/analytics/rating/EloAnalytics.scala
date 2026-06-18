package chess.analytics.rating

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions._
import chess.analytics.config.EloConfig

import scala.collection.mutable

final case class EloRatingResult(
  botId:                 String,
  rating:                Double,
  ratingChange:          Double,
  gamesPlayed:           Long,
  wins:                  Long,
  draws:                 Long,
  losses:                Long,
  averageOpponentRating: Double,
  lastGameTimestamp:     String
)

object EloAnalytics {

  def computeRatings(gameFinished: DataFrame, config: EloConfig = EloConfig.fromEnv()): DataFrame = {
    val spark = gameFinished.sparkSession
    import spark.implicits._

    val states = mutable.Map.empty[String, BotRatingState]

    val games = gameFinished
      .select(
        col("timestamp"),
        col("gameId"),
        col("whiteBotId"),
        col("blackBotId"),
        col("result")
      )
      .orderBy(col("timestamp"), col("gameId"))
      .collect()

    games.foreach { row =>
      val whiteBotId = row.getAs[String]("whiteBotId")
      val blackBotId = row.getAs[String]("blackBotId")
      val result = row.getAs[String]("result")

      if (whiteBotId != null && blackBotId != null && result != null) {
        val white = stateFor(states, whiteBotId, config.initialRating)
        val black = stateFor(states, blackBotId, config.initialRating)

        val whiteScore = scoreForWhite(result)
        val blackScore = 1.0 - whiteScore
        val whiteExpected = expectedScore(white.rating, black.rating)
        val blackExpected = expectedScore(black.rating, white.rating)
        val whiteBefore = white.rating
        val blackBefore = black.rating

        white.rating = white.rating + config.kFactor * (whiteScore - whiteExpected)
        black.rating = black.rating + config.kFactor * (blackScore - blackExpected)

        white.gamesPlayed += 1L
        black.gamesPlayed += 1L
        white.opponentRatingTotal += blackBefore
        black.opponentRatingTotal += whiteBefore
        white.lastGameTimestamp = row.getAs[String]("timestamp")
        black.lastGameTimestamp = row.getAs[String]("timestamp")

        result match {
          case "white" =>
            white.wins += 1L
            black.losses += 1L
          case "black" =>
            black.wins += 1L
            white.losses += 1L
          case "draw" =>
            white.draws += 1L
            black.draws += 1L
          case _ =>
        }
      }
    }

    states.values.toSeq
      .map(_.toResult(config.initialRating))
      .toDS()
      .toDF()
      .orderBy(desc("rating"), desc("wins"), col("botId"))
  }

  private def stateFor(
    states:        mutable.Map[String, BotRatingState],
    botId:         String,
    initialRating: Double
  ): BotRatingState =
    states.getOrElseUpdate(botId, BotRatingState(botId = botId, rating = initialRating))

  private def expectedScore(rating: Double, opponentRating: Double): Double =
    1.0 / (1.0 + math.pow(10.0, (opponentRating - rating) / 400.0))

  private def scoreForWhite(result: String): Double =
    result match {
      case "white" => 1.0
      case "black" => 0.0
      case "draw"  => 0.5
      case _       => 0.5
    }

  private final case class BotRatingState(
    botId:               String,
    var rating:          Double,
    var gamesPlayed:     Long   = 0L,
    var wins:            Long   = 0L,
    var draws:           Long   = 0L,
    var losses:          Long   = 0L,
    var opponentRatingTotal: Double = 0.0,
    var lastGameTimestamp:   String = ""
  ) {
    def toResult(initialRating: Double): EloRatingResult =
      EloRatingResult(
        botId                 = botId,
        rating                = round3(rating),
        ratingChange          = round3(rating - initialRating),
        gamesPlayed           = gamesPlayed,
        wins                  = wins,
        draws                 = draws,
        losses                = losses,
        averageOpponentRating = if (gamesPlayed == 0L) 0.0 else round3(opponentRatingTotal / gamesPlayed),
        lastGameTimestamp     = lastGameTimestamp
      )

    private def round3(value: Double): Double =
      BigDecimal(value).setScale(3, BigDecimal.RoundingMode.HALF_UP).toDouble
  }
}
