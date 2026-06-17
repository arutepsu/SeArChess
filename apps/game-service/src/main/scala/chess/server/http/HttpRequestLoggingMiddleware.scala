package chess.server.http

import cats.data.Kleisli
import cats.effect.IO
import chess.observability.{CorrelationContext, StructuredLog, TraceReporter}
import org.http4s.{Headers, HttpApp}
import org.typelevel.ci.CIString

import java.util.UUID
import scala.concurrent.duration.{FiniteDuration, MICROSECONDS}

/** Middleware that logs one structured line per HTTP request for performance correlation.
  *
  * Successful responses emit a `request_completed` event via [[StructuredLog.info]]. Failed
  * request effects (where the inner app raises rather than returns a response) emit a
  * `request_failed` event via [[StructuredLog.error]] and then re-raise the original error
  * unchanged.
  *
  * Captured fields for both events: method, normalized route, durationMs, and the four
  * performance correlation headers (`X-Performance-Run-Id`, `X-Performance-Tool`,
  * `X-Performance-Workload`, `X-Performance-Phase`). Absent headers are recorded as `"none"`.
  * Failed events additionally include `errorType` and `errorMessage`. The `status` field is only
  * present on `request_completed` because a failed effect produces no HTTP response.
  *
  * No request or response bodies are read. No user data is captured.
  */
object HttpRequestLoggingMiddleware:

  private val Fallback = "none"

  def apply(inner: HttpApp[IO]): HttpApp[IO] =
    Kleisli { request =>
      val startNs = System.nanoTime()
      val correlationId = extractCorrelationId(request.headers)
      IO(CorrelationContext.push(correlationId)).flatMap { previousCorrelationId =>
        inner.run(request).attempt.guarantee(IO(CorrelationContext.restore(previousCorrelationId)))
      }.flatMap { result =>
        val durationMs = (System.nanoTime() - startNs).toDouble / 1e6
        val route      = HttpMetricsMiddleware.normalizeRoute(request.pathInfo.renderString)
        TraceReporter.emit(
          "game-service",
          s"${request.method.name} $route",
          correlationId,
          FiniteDuration(math.max(1L, (durationMs * 1000).toLong), MICROSECONDS),
          "http.method" -> request.method.name,
          "http.route" -> route,
          "http.path" -> request.pathInfo.renderString
        )
        result match
          case Right(response) =>
            IO(
              StructuredLog.info(
                "game-service",
                "request_completed",
                "method"              -> request.method.name,
                "route"               -> route,
                "status"              -> response.status.code,
                "durationMs"          -> durationMs,
                "correlationId"       -> correlationId,
                "performanceRunId"    -> extractPerfHeader(request.headers, "X-Performance-Run-Id"),
                "performanceTool"     -> extractPerfHeader(request.headers, "X-Performance-Tool"),
                "performanceWorkload" -> extractPerfHeader(request.headers, "X-Performance-Workload"),
                "performancePhase"    -> extractPerfHeader(request.headers, "X-Performance-Phase")
              )
            ).as(response)
          case Left(error) =>
            IO(
              StructuredLog.error(
                "game-service",
                "request_failed",
                "method"              -> request.method.name,
                "route"               -> route,
                "durationMs"          -> durationMs,
                "correlationId"       -> correlationId,
                "performanceRunId"    -> extractPerfHeader(request.headers, "X-Performance-Run-Id"),
                "performanceTool"     -> extractPerfHeader(request.headers, "X-Performance-Tool"),
                "performanceWorkload" -> extractPerfHeader(request.headers, "X-Performance-Workload"),
                "performancePhase"    -> extractPerfHeader(request.headers, "X-Performance-Phase"),
                "errorType"           -> error.getClass.getSimpleName,
                "errorMessage"        -> safeMessage(error)
              )
            ).flatMap(_ => IO.raiseError(error))
      }
    }

  private[http] def extractPerfHeader(headers: Headers, name: String): String =
    headers.get(CIString(name)).fold(Fallback)(_.head.value)

  private def extractCorrelationId(headers: Headers): String =
    headers
      .get(CIString("X-Correlation-Id"))
      .orElse(headers.get(CIString("X-Request-Id")))
      .fold(UUID.randomUUID().toString)(_.head.value)

  private def safeMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
