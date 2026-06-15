package chess.tournamentservice

import chess.observability.StructuredLog

final case class TournamentServiceConfig(
    host: String,
    port: Int,
    outputBasePath: String,
    maxParallelJobs: Int,
    analyticsEnabled: Boolean,
    analyticsOutputBasePath: String,
    maxParallelAnalyticsJobs: Int,
    analyticsSbtCommand: List[String],
    stockfishPath: Option[String],
    searchessAiBaseUrl: Option[String]
)

object TournamentServiceConfig:

  def loadOrExit(): TournamentServiceConfig =
    load().fold(
      err => {
        StructuredLog.error("tournament-service", "configuration_error", "error" -> err)
        sys.exit(1)
      },
      identity
    )

  def load(
      env: String => Option[String] = key => Option(System.getenv(key)).filter(_.nonEmpty),
      osName: String = System.getProperty("os.name", "")
  ): Either[String, TournamentServiceConfig] =
    for
      port            <- parsePort("TOURNAMENT_HTTP_PORT", env("TOURNAMENT_HTTP_PORT").getOrElse("8085"))
      outputBasePath  <- nonEmpty("TOURNAMENT_OUTPUT_BASE_PATH", env("TOURNAMENT_OUTPUT_BASE_PATH").getOrElse("target/arena/tournament-jobs"))
      maxParallelJobs <- parsePositiveInt("TOURNAMENT_MAX_PARALLEL_JOBS", env("TOURNAMENT_MAX_PARALLEL_JOBS").getOrElse("1"))
      analyticsEnabled <- parseBoolean("TOURNAMENT_ANALYTICS_ENABLED", env("TOURNAMENT_ANALYTICS_ENABLED").getOrElse("true"))
      analyticsOutputBasePath <- nonEmpty(
        "TOURNAMENT_ANALYTICS_OUTPUT_BASE_PATH",
        env("TOURNAMENT_ANALYTICS_OUTPUT_BASE_PATH").getOrElse("target/spark-analytics/tournament-jobs")
      )
      maxParallelAnalyticsJobs <- parsePositiveInt(
        "TOURNAMENT_MAX_PARALLEL_ANALYTICS_JOBS",
        env("TOURNAMENT_MAX_PARALLEL_ANALYTICS_JOBS").getOrElse("1")
      )
      analyticsSbtCommand <- parseCommand(
        "TOURNAMENT_ANALYTICS_SBT_COMMAND",
        env("TOURNAMENT_ANALYTICS_SBT_COMMAND").getOrElse(defaultSbtCommand(osName).mkString(" "))
      )
    yield TournamentServiceConfig(
      host               = env("TOURNAMENT_HTTP_HOST").getOrElse("0.0.0.0"),
      port               = port,
      outputBasePath     = outputBasePath,
      maxParallelJobs    = maxParallelJobs,
      analyticsEnabled   = analyticsEnabled,
      analyticsOutputBasePath = analyticsOutputBasePath,
      maxParallelAnalyticsJobs = maxParallelAnalyticsJobs,
      analyticsSbtCommand = analyticsSbtCommand,
      stockfishPath      = env("STOCKFISH_PATH"),
      searchessAiBaseUrl = env("SEARCHESS_AI_BASE_URL")
    )

  private def nonEmpty(name: String, value: String): Either[String, String] =
    val trimmed = value.trim
    if trimmed.nonEmpty then Right(trimmed)
    else Left(s"$name must be non-empty")

  private def parsePort(name: String, value: String): Either[String, Int] =
    value.toIntOption match
      case Some(p) if p >= 1 && p <= 65535 => Right(p)
      case Some(p)                         => Left(s"$name must be between 1 and 65535, got: $p")
      case None                            => Left(s"$name must be an integer, got: '$value'")

  private def parsePositiveInt(name: String, value: String): Either[String, Int] =
    value.toIntOption match
      case Some(p) if p > 0 => Right(p)
      case Some(p)          => Left(s"$name must be positive, got: $p")
      case None             => Left(s"$name must be an integer, got: '$value'")

  private def parseBoolean(name: String, value: String): Either[String, Boolean] =
    value.trim.toLowerCase match
      case "true"  => Right(true)
      case "false" => Right(false)
      case other   => Left(s"$name must be true or false, got: '$other'")

  private[tournamentservice] def defaultSbtCommand(osName: String): List[String] =
    if osName.toLowerCase.contains("windows") then List("cmd.exe", "/c", "sbt")
    else List("sbt")

  private[tournamentservice] def parseCommand(name: String, value: String): Either[String, List[String]] =
    val trimmed = value.trim
    if trimmed.isEmpty then Left(s"$name must be non-empty")
    else
      val tokens = splitCommand(trimmed)
      if tokens.nonEmpty then Right(tokens)
      else Left(s"$name must contain at least one command token")

  private def splitCommand(value: String): List[String] =
    val tokens = scala.collection.mutable.ListBuffer.empty[String]
    val current = StringBuilder()
    var quoted = false
    var quoteChar = ' '

    value.foreach { ch =>
      if quoted then
        if ch == quoteChar then quoted = false
        else current.append(ch)
      else if ch == '"' || ch == '\'' then
        quoted = true
        quoteChar = ch
      else if ch.isWhitespace then
        if current.nonEmpty then
          tokens += current.toString
          current.clear()
      else current.append(ch)
    }

    if current.nonEmpty then tokens += current.toString
    tokens.toList
