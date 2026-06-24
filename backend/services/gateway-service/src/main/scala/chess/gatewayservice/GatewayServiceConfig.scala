package chess.gatewayservice

import chess.observability.StructuredLog

final case class GatewayServiceConfig(
  host: String,
  port: Int,
  tournamentServerUrl: String,
  userServiceUrl: String,
  authDisabled: Boolean,
  devUserName: String,
  runnerSecret: Option[String]
)

object GatewayServiceConfig:

  def loadOrExit(): GatewayServiceConfig =
    load().fold(
      err => {
        StructuredLog.error("gateway-service", "configuration_error", "error" -> err)
        sys.exit(1)
      },
      identity
    )

  def load(
    env: String => Option[String] = key => Option(System.getenv(key)).filter(_.nonEmpty)
  ): Either[String, GatewayServiceConfig] =
    for
      port <- parsePort("GATEWAY_HTTP_PORT", env("GATEWAY_HTTP_PORT").getOrElse("8087"))
    yield GatewayServiceConfig(
      host                = env("GATEWAY_HTTP_HOST").getOrElse("0.0.0.0"),
      port                = port,
      tournamentServerUrl = env("TOURNAMENT_SERVER_URL").getOrElse("http://141.37.123.132:8086"),
      userServiceUrl      = env("USER_SERVICE_URL").getOrElse("http://localhost:8082"),
      authDisabled        = env("GATEWAY_AUTH_DISABLED").exists(_.equalsIgnoreCase("true")),
      devUserName         = env("GATEWAY_DEV_USER_NAME").getOrElse("dev-director"),
      runnerSecret        = env("GATEWAY_RUNNER_SECRET")
    )

  private def parsePort(name: String, value: String): Either[String, Int] =
    value.toIntOption match
      case Some(p) if p >= 1 && p <= 65535 => Right(p)
      case Some(p)                          => Left(s"$name must be between 1 and 65535, got: $p")
      case None                             => Left(s"$name must be an integer, got: '$value'")
