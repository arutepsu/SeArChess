package chess.analytics

final case class SparkLakeConfig(
  writeEnabled:     Boolean = false,
  basePath:         String  = "target/spark-lake",
  writeMode:        String  = "overwrite",
  partitionByRunId: Boolean = true
)

object SparkLakeConfig {

  def fromEnv(): SparkLakeConfig = fromEnvMap(sys.env)

  private[analytics] def fromEnvMap(env: Map[String, String]): SparkLakeConfig = {
    val enabled = env.getOrElse("SPARK_LAKE_WRITE_ENABLED", "false").toLowerCase == "true"
    val basePath = env.getOrElse("SPARK_LAKE_BASE_PATH", "target/spark-lake")
    val writeMode = normalizeWriteMode(env.getOrElse("SPARK_LAKE_WRITE_MODE", "overwrite"))
    val partitionByRunId = env.getOrElse("SPARK_LAKE_PARTITION_BY_RUN_ID", "true").toLowerCase != "false"

    SparkLakeConfig(
      writeEnabled     = enabled,
      basePath         = basePath,
      writeMode        = writeMode,
      partitionByRunId = partitionByRunId
    )
  }

  private def normalizeWriteMode(value: String): String =
    value.toLowerCase match {
      case "append" | "overwrite" => value.toLowerCase
      case other =>
        println(s"[WARN] Unsupported SPARK_LAKE_WRITE_MODE=$other. Falling back to overwrite.")
        "overwrite"
    }
}
