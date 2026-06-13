package chess.analytics

import org.apache.spark.sql.SparkSession

object GameAnalyticsJob {

  def main(args: Array[String]): Unit = {
    val inputPath  = if (args.length > 0) args(0) else "docs/samples/generated-heuristic-tournament.jsonl"
    val outputPath = if (args.length > 1) args(1) else "target/spark-analytics"

    val spark = SparkSession.builder()
      .appName("Arena Game Analytics")
      .master("local[*]")
      .config("spark.sql.shuffle.partitions", "4")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .getOrCreate()

    spark.sparkContext.setLogLevel("WARN")

    val pgConfig = PostgresConfig.fromEnv()

    try {
      GameAnalytics.run(spark, inputPath, outputPath, pgConfig)
    } finally {
      spark.stop()
    }
  }
}
