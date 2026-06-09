package chess.userservice

import chess.observability.StructuredLog
import chess.userservice.application.LichessOAuthConfig

final case class UserServiceConfig(
    host: String,
    port: Int,
    postgresUrl: String,
    postgresUser: String,
    postgresPassword: String,
    postgresSchema: Option[String],
    lichessOAuth: LichessOAuthConfig,
    internalApiKey: String
)

object UserServiceConfig:
  def loadOrExit(): UserServiceConfig =
    load().fold(
      err => {
        StructuredLog.error("user-service", "configuration_error", "error" -> err)
        sys.exit(1)
      },
      identity
    )

  def load(
      env: String => Option[String] = key => Option(System.getenv(key)).filter(_.nonEmpty)
  ): Either[String, UserServiceConfig] =
    for
      port        <- parsePort("USER_HTTP_PORT", env("USER_HTTP_PORT").getOrElse("8082"))
      postgresUrl <- env("USER_POSTGRES_URL").toRight("USER_POSTGRES_URL is required")
    yield UserServiceConfig(
      host             = env("USER_HTTP_HOST").getOrElse("0.0.0.0"),
      port             = port,
      postgresUrl      = postgresUrl,
      postgresUser     = env("USER_POSTGRES_USER").getOrElse("searchess"),
      postgresPassword = env("USER_POSTGRES_PASSWORD").getOrElse(""),
      postgresSchema   = env("USER_POSTGRES_SCHEMA"),
      lichessOAuth = LichessOAuthConfig(
        clientId         = env("LICHESS_OAUTH_CLIENT_ID").getOrElse(""),
        authorizeUrl     = env("LICHESS_OAUTH_AUTHORIZE_URL").getOrElse("https://lichess.org/oauth"),
        tokenUrl         = env("LICHESS_OAUTH_TOKEN_URL").getOrElse("https://lichess.org/api/token"),
        accountUrl       = env("LICHESS_ACCOUNT_URL").getOrElse("https://lichess.org/api/account"),
        redirectUri      = env("LICHESS_OAUTH_REDIRECT_URI").getOrElse(""),
        stateTtlSeconds  = env("LICHESS_OAUTH_STATE_TTL_SECONDS").flatMap(_.toLongOption).getOrElse(600L),
        webUiSettingsUrl = env("WEB_UI_SETTINGS_URL").getOrElse("http://localhost:10000/settings")
      ),
      internalApiKey = env("USER_SERVICE_INTERNAL_API_KEY").getOrElse("")
    )

  private def parsePort(name: String, value: String): Either[String, Int] =
    value.toIntOption match
      case Some(p) if p >= 1 && p <= 65535 => Right(p)
      case Some(p)                          => Left(s"$name must be between 1 and 65535, got: $p")
      case None                             => Left(s"$name must be an integer, got: '$value'")
