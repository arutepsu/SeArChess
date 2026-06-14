package chess.analytics

import org.apache.spark.sql.{Column, DataFrame}
import org.apache.spark.sql.functions._

object DataQualityChecks {

  def gameFinishedChecks(gameFinished: DataFrame): DataFrame = {
    val checks = Seq(
      check(gameFinished, "game_finished_missing_game_id", missing("gameId"), "error"),
      check(gameFinished, "game_finished_missing_tournament_id", missing("tournamentId"), "error"),
      check(
        gameFinished,
        "game_finished_invalid_result",
        col("result").isNull || !col("result").isin("white", "black", "draw"),
        "error"
      ),
      check(gameFinished, "game_finished_negative_total_ply", col("totalPly") < 0, "error"),
      check(gameFinished, "game_finished_negative_duration_millis", col("durationMillis") < 0, "error"),
      check(gameFinished, "game_finished_missing_white_bot_id", missing("whiteBotId"), "error"),
      check(gameFinished, "game_finished_missing_black_bot_id", missing("blackBotId"), "error"),
      check(
        gameFinished,
        "game_finished_missing_winner_on_decisive_result",
        col("result").isin("white", "black") && missing("winnerBotId"),
        "error"
      ),
      check(
        gameFinished,
        "game_finished_winner_present_on_draw",
        col("result") === "draw" && present("winnerBotId"),
        "warn"
      )
    )

    checks.reduce(_.unionByName(_))
  }

  private def check(df: DataFrame, checkName: String, failedWhen: Column, severity: String): DataFrame =
    df.agg(coalesce(sum(when(failedWhen, 1L).otherwise(0L)), lit(0L)).cast("long").as("failedRows"))
      .select(
        lit(checkName).as("checkName"),
        col("failedRows"),
        lit(severity).as("severity")
      )

  private def missing(columnName: String): Column =
    col(columnName).isNull || trim(col(columnName)) === ""

  private def present(columnName: String): Column =
    col(columnName).isNotNull && trim(col(columnName)) =!= ""
}
