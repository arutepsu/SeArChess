package chess.observability

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

object TraceReporter:

  private val DefaultEndpoint = "http://tempo:9411/api/v2/spans"
  private val Endpoint =
    sys.env.get("TRACE_ZIPKIN_ENDPOINT")
      .orElse(sys.env.get("OTEL_ZIPKIN_ENDPOINT"))
      .getOrElse(DefaultEndpoint)

  private val Enabled =
    sys.env.get("TRACE_EXPORT_ENABLED").forall(value => value.equalsIgnoreCase("true") || value == "1")

  private val Client =
    HttpClient
      .newBuilder()
      .connectTimeout(java.time.Duration.ofMillis(500))
      .build()

  def emit(
      service: String,
      name: String,
      correlationId: String,
      duration: FiniteDuration,
      tags: (String, Any)*
  ): Unit =
    if Enabled then
      val traceId = traceIdFrom(correlationId)
      val spanId  = spanIdFrom(s"$correlationId:$service:$name:${Instant.now().toEpochMilli}")
      val nowMicros = TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis())
      val durationMicros = math.max(1L, duration.toMicros)
      val tagJson =
        (Seq(
          "correlation.id" -> correlationId,
          "service.name" -> service
        ) ++ tags)
          .map { case (key, value) => quote(key) + ":" + quote(renderTagValue(value)) }
          .mkString("{", ",", "}")
      val body =
        s"""[{"traceId":"$traceId","id":"$spanId","name":${quote(name)},"timestamp":$nowMicros,"duration":$durationMicros,"localEndpoint":{"serviceName":${quote(service)}},"tags":$tagJson}]"""

      try
        val request =
          HttpRequest
            .newBuilder(URI.create(Endpoint))
            .timeout(java.time.Duration.ofMillis(700))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        Client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
        ()
      catch
        case NonFatal(error) =>
          StructuredLog.warn(
            service,
            "trace_export_failed",
            "span" -> name,
            "correlationId" -> correlationId,
            "error" -> Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
          )

  private def traceIdFrom(value: String): String =
    sha256Hex(value).take(32)

  private def spanIdFrom(value: String): String =
    sha256Hex(value).take(16)

  private def sha256Hex(value: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
    digest.map(byte => f"${byte & 0xff}%02x").mkString

  private def renderTagValue(value: Any): String = value match
    case null       => "null"
    case None       => "none"
    case Some(v)    => renderTagValue(v)
    case instant: Instant => instant.toString
    case other      => other.toString

  private def quote(value: String): String =
    "\"" + value.flatMap {
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case '\b'         => "\\b"
      case '\f'         => "\\f"
      case '\n'         => "\\n"
      case '\r'         => "\\r"
      case '\t'         => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    } + "\""
