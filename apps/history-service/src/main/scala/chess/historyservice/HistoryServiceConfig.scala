package chess.historyservice

import chess.observability.StructuredLog

final case class HistoryServiceConfig(
  host:               String,
  port:               Int,
  gameServiceBaseUrl: String,
  dbPath:             String,
  timeoutMillis:      Int
)

object HistoryServiceConfig:
  def loadOrExit(): HistoryServiceConfig =
    load().fold(
      err => {
        StructuredLog.error("history-service", "configuration_error", "error" -> err)
        sys.exit(1)
      },
      identity
    )

  def load(env: String => Option[String] = key => Option(System.getenv(key)).filter(_.nonEmpty)): Either[String, HistoryServiceConfig] =
    for
      port    <- parsePort("HISTORY_HTTP_PORT", env("HISTORY_HTTP_PORT").getOrElse("8081"))
      timeout <- parsePositiveInt("HISTORY_GAME_SERVICE_TIMEOUT_MILLIS", env("HISTORY_GAME_SERVICE_TIMEOUT_MILLIS").getOrElse("2000"))
      baseUrl  = env("GAME_SERVICE_BASE_URL").getOrElse("http://127.0.0.1:8080")
      dbPath   = env("HISTORY_DB_PATH").getOrElse("history.sqlite")
    yield HistoryServiceConfig(
      host               = env("HISTORY_HTTP_HOST").getOrElse("0.0.0.0"),
      port               = port,
      gameServiceBaseUrl = baseUrl,
      dbPath             = dbPath,
      timeoutMillis      = timeout
    )

  private def parsePort(name: String, value: String): Either[String, Int] =
    value.toIntOption match
      case Some(p) if p >= 1 && p <= 65535 => Right(p)
      case Some(p) => Left(s"$name must be between 1 and 65535, got: $p")
      case None => Left(s"$name must be an integer, got: '$value'")

  private def parsePositiveInt(name: String, value: String): Either[String, Int] =
    value.toIntOption match
      case Some(n) if n >= 1 => Right(n)
      case Some(n) => Left(s"$name must be >= 1, got: $n")
      case None => Left(s"$name must be an integer, got: '$value'")
