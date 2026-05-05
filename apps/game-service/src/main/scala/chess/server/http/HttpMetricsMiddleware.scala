package chess.server.http

import cats.data.Kleisli
import cats.effect.IO
import org.http4s.HttpApp

/** Middleware that records HTTP request counts and durations into [[HttpMetricsRegistry]].
  *
  * Wraps the composed `HttpApp[IO]` so that every routed and unmatched request is measured.
  * Timing uses `System.nanoTime()` for monotonic precision unaffected by clock adjustments.
  *
  * Route labels are normalized to low-cardinality templates so that dynamic path segments
  * (game IDs, session IDs, etc.) never create unbounded Prometheus label cardinality.
  *
  * No request or response bodies are read. No user data is captured.
  */
object HttpMetricsMiddleware:

  def apply(registry: HttpMetricsRegistry, inner: HttpApp[IO]): HttpApp[IO] =
    Kleisli { request =>
      val startNs = System.nanoTime()
      inner.run(request).flatMap { response =>
        val durationSeconds = (System.nanoTime() - startNs).toDouble / 1e9
        val route           = normalizeRoute(request.pathInfo.renderString)
        val status          = response.status.code.toString
        IO(registry.record(request.method.name, route, status, durationSeconds)).as(response)
      }
    }

  private[http] def normalizeRoute(path: String): String =
    path.split('/').toList match
      case "" :: "health" :: Nil                              => "/health"
      case "" :: "metrics" :: Nil                             => "/metrics"
      case "" :: "sessions" :: Nil                            => "/sessions"
      case "" :: "sessions" :: "import" :: Nil                => "/sessions/import"
      case "" :: "sessions" :: "import-notation" :: Nil       => "/sessions/import-notation"
      case "" :: "sessions" :: _ :: Nil                       => "/sessions/{sessionId}"
      case "" :: "sessions" :: _ :: "state" :: Nil            => "/sessions/{sessionId}/state"
      case "" :: "sessions" :: _ :: "export" :: Nil           => "/sessions/{sessionId}/export"
      case "" :: "sessions" :: _ :: "cancel" :: Nil           => "/sessions/{sessionId}/cancel"
      case "" :: "games" :: _ :: Nil                          => "/games/{gameId}"
      case "" :: "games" :: _ :: "legal-moves" :: Nil         => "/games/{gameId}/legal-moves"
      case "" :: "games" :: _ :: "moves" :: Nil               => "/games/{gameId}/moves"
      case "" :: "games" :: _ :: "resign" :: Nil              => "/games/{gameId}/resign"
      case "" :: "games" :: _ :: "ai-move" :: Nil             => "/games/{gameId}/ai-move"
      case "" :: "games" :: _ :: "notation" :: "fen" :: Nil   => "/games/{gameId}/notation/fen"
      case "" :: "games" :: _ :: "notation" :: "pgn" :: Nil   => "/games/{gameId}/notation/pgn"
      case "" :: "archive" :: "games" :: _ :: Nil             => "/archive/games/{gameId}"
      case "" :: "ops" :: "history-outbox" :: Nil             => "/ops/history-outbox"
      case "" :: "ops" :: "history-outbox" :: "pending" :: Nil => "/ops/history-outbox/pending"
      case "" :: "ops" :: "history-outbox" :: _ :: Nil        => "/ops/history-outbox/{id}"
      case "" :: "admin" :: _                                  => "/admin/{...}"
      case _                                                   => "unknown"
