package chess.server.http

import cats.data.Kleisli
import cats.effect.IO
import chess.observability.StructuredLog
import org.http4s.{Headers, HttpApp}
import org.typelevel.ci.CIString

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
      inner.run(request).attempt.flatMap { result =>
        val durationMs = (System.nanoTime() - startNs).toDouble / 1e6
        val route      = HttpMetricsMiddleware.normalizeRoute(request.pathInfo.renderString)
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

  private def safeMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
