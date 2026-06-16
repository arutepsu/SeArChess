package chess.analyticsservice

import chess.observability.StructuredLog

final case class AnalyticsServiceConfig(
  host: String,
  port: Int,
  postgresUrl: String,
  postgresUser: String,
  postgresPassword: String,
  postgresSchema: String
)

object AnalyticsServiceConfig:

  def loadOrExit(): AnalyticsServiceConfig =
    load().fold(
      err => {
        StructuredLog.error("analytics-service", "configuration_error", "error" -> err)
        sys.exit(1)
      },
      identity
    )

  def load(
    env: String => Option[String] = key => Option(System.getenv(key)).filter(_.nonEmpty)
  ): Either[String, AnalyticsServiceConfig] =
    for
      port        <- parsePort("ANALYTICS_HTTP_PORT", env("ANALYTICS_HTTP_PORT").getOrElse("8084"))
      postgresUrl <- env("ANALYTICS_POSTGRES_URL").toRight("ANALYTICS_POSTGRES_URL is required")
      schema      <- parseSchema(env("ANALYTICS_POSTGRES_SCHEMA").getOrElse("public"))
    yield AnalyticsServiceConfig(
      host             = env("ANALYTICS_HTTP_HOST").getOrElse("0.0.0.0"),
      port             = port,
      postgresUrl      = postgresUrl,
      postgresUser     = env("ANALYTICS_POSTGRES_USER").getOrElse("searchess"),
      postgresPassword = env("ANALYTICS_POSTGRES_PASSWORD").getOrElse(""),
      postgresSchema   = schema
    )

  private val schemaPattern = "^[A-Za-z_][A-Za-z0-9_]*$".r

  private def parseSchema(raw: String): Either[String, String] =
    if schemaPattern.matches(raw) then Right(raw)
    else Left(s"ANALYTICS_POSTGRES_SCHEMA must match [A-Za-z_][A-Za-z0-9_]*, got: '$raw'")

  private def parsePort(name: String, value: String): Either[String, Int] =
    value.toIntOption match
      case Some(p) if p >= 1 && p <= 65535 => Right(p)
      case Some(p)                          => Left(s"$name must be between 1 and 65535, got: $p")
      case None                             => Left(s"$name must be an integer, got: '$value'")
