package chess.aiservice

import chess.observability.StructuredLog

final case class AiServiceConfig(
  host:      String,
  port:      Int,
  engineId:  String
)

object AiServiceConfig:
  def loadOrExit(): AiServiceConfig =
    load().fold(
      err => {
        StructuredLog.error("ai-service", "configuration_error", "error" -> err)
        sys.exit(1)
      },
      identity
    )

  def load(env: String => Option[String] = key => Option(System.getenv(key)).filter(_.nonEmpty)): Either[String, AiServiceConfig] =
    for
      port <- parsePort("AI_HTTP_PORT", env("AI_HTTP_PORT").getOrElse("8765"))
    yield AiServiceConfig(
      host     = env("AI_HTTP_HOST").getOrElse("0.0.0.0"),
      port     = port,
      engineId = env("AI_ENGINE_ID").getOrElse("random-legal")
    )

  private def parsePort(name: String, value: String): Either[String, Int] =
    value.toIntOption match
      case Some(p) if p >= 1 && p <= 65535 => Right(p)
      case Some(p)                         => Left(s"$name must be between 1 and 65535, got: $p")
      case None                            => Left(s"$name must be an integer, got: '$value'")
