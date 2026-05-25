package chess.aiservice

import chess.observability.StructuredLog

final case class AiServiceConfig(
    host: String,
    port: Int,
    engineId: String,
    /** Base URL of the Python AI service (e.g. http://python-ai-service:8765).
      * When set, move-suggestion requests are proxied there with fallback to local selection.
      * Controlled by the PYTHON_AI_BASE_URL environment variable.
      */
    pythonAiBaseUrl: Option[String],
    pythonAiTimeoutMillis: Int
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

  def load(
      env: String => Option[String] = key => Option(System.getenv(key)).filter(_.nonEmpty)
  ): Either[String, AiServiceConfig] =
    for port <- parsePort("AI_HTTP_PORT", env("AI_HTTP_PORT").getOrElse("8765"))
    yield AiServiceConfig(
      host = env("AI_HTTP_HOST").getOrElse("0.0.0.0"),
      port = port,
      engineId = env("AI_ENGINE_ID").getOrElse("random-legal"),
      pythonAiBaseUrl = env("PYTHON_AI_BASE_URL"),
      pythonAiTimeoutMillis = env("PYTHON_AI_TIMEOUT_MILLIS")
        .flatMap(_.toIntOption)
        .getOrElse(5000)
    )

  private def parsePort(name: String, value: String): Either[String, Int] =
    value.toIntOption match
      case Some(p) if p >= 1 && p <= 65535 => Right(p)
      case Some(p)                         => Left(s"$name must be between 1 and 65535, got: $p")
      case None                            => Left(s"$name must be an integer, got: '$value'")
