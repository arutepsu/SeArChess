package chess.tournamentservice

import chess.observability.StructuredLog

final case class TournamentServiceConfig(
    host: String,
    port: Int,
    outputBasePath: String,
    maxParallelJobs: Int,
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
      env: String => Option[String] = key => Option(System.getenv(key)).filter(_.nonEmpty)
  ): Either[String, TournamentServiceConfig] =
    for
      port            <- parsePort("TOURNAMENT_HTTP_PORT", env("TOURNAMENT_HTTP_PORT").getOrElse("8085"))
      outputBasePath  <- nonEmpty("TOURNAMENT_OUTPUT_BASE_PATH", env("TOURNAMENT_OUTPUT_BASE_PATH").getOrElse("target/arena/tournament-jobs"))
      maxParallelJobs <- parsePositiveInt("TOURNAMENT_MAX_PARALLEL_JOBS", env("TOURNAMENT_MAX_PARALLEL_JOBS").getOrElse("1"))
    yield TournamentServiceConfig(
      host               = env("TOURNAMENT_HTTP_HOST").getOrElse("0.0.0.0"),
      port               = port,
      outputBasePath     = outputBasePath,
      maxParallelJobs    = maxParallelJobs,
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
