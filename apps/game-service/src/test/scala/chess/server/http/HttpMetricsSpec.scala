package chess.server.http

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HttpMetricsSpec extends AnyFlatSpec with Matchers:

  // ─── HttpMetricsRegistry ──────────────────────────────────────────────────

  "HttpMetricsRegistry.renderPrometheusText" should "return empty string when no requests recorded" in:
    val registry = new HttpMetricsRegistry
    registry.renderPrometheusText() shouldBe ""

  it should "include http_requests_total counter after one record" in:
    val registry = new HttpMetricsRegistry
    registry.record("GET", "/sessions", "200", 0.01)
    val text = registry.renderPrometheusText()
    text should include("# HELP http_requests_total")
    text should include("# TYPE http_requests_total counter")
    text should include("""http_requests_total{method="GET",route="/sessions",status="200"} 1""")

  it should "increment counter on repeated records for same key" in:
    val registry = new HttpMetricsRegistry
    registry.record("GET", "/sessions", "200", 0.01)
    registry.record("GET", "/sessions", "200", 0.02)
    registry.record("GET", "/sessions", "200", 0.03)
    val text = registry.renderPrometheusText()
    text should include("""http_requests_total{method="GET",route="/sessions",status="200"} 3""")

  it should "track separate keys for different methods, routes, and statuses" in:
    val registry = new HttpMetricsRegistry
    registry.record("GET",  "/sessions",            "200", 0.01)
    registry.record("POST", "/sessions",            "201", 0.05)
    registry.record("GET",  "/sessions/{sessionId}", "404", 0.002)
    val text = registry.renderPrometheusText()
    text should include("""http_requests_total{method="GET",route="/sessions",status="200"} 1""")
    text should include("""http_requests_total{method="POST",route="/sessions",status="201"} 1""")
    text should include("""http_requests_total{method="GET",route="/sessions/{sessionId}",status="404"} 1""")

  it should "include histogram HELP and TYPE lines" in:
    val registry = new HttpMetricsRegistry
    registry.record("GET", "/health", "200", 0.001)
    val text = registry.renderPrometheusText()
    text should include("# HELP http_request_duration_seconds")
    text should include("# TYPE http_request_duration_seconds histogram")

  it should "set +Inf bucket equal to total request count" in:
    val registry = new HttpMetricsRegistry
    registry.record("GET", "/health", "200", 0.001)
    registry.record("GET", "/health", "200", 0.3)
    registry.record("GET", "/health", "200", 6.0)
    val text = registry.renderPrometheusText()
    text should include("""http_request_duration_seconds_bucket{method="GET",route="/health",status="200",le="+Inf"} 3""")

  it should "count fast request in all buckets above its duration" in:
    val registry = new HttpMetricsRegistry
    registry.record("POST", "/sessions", "201", 0.003) // ≤ 0.005
    val text = registry.renderPrometheusText()
    // 0.003 ≤ 0.005 so the 0.005 bucket should have count 1
    text should include("""le="0.005"} 1""")
    // and so should all larger buckets up to +Inf
    text should include("""le="5.0"} 1""")
    text should include("""le="+Inf"} 1""")

  it should "not count slow request in fast buckets" in:
    val registry = new HttpMetricsRegistry
    registry.record("POST", "/sessions", "201", 1.5) // between 1.0 and 2.5
    val text = registry.renderPrometheusText()
    // 1.5 > 1.0, so the 1.0 bucket must be 0
    text should include("""le="1.0"} 0""")
    // 1.5 ≤ 2.5, so the 2.5 bucket must be 1
    text should include("""le="2.5"} 1""")

  it should "include _sum and _count lines" in:
    val registry = new HttpMetricsRegistry
    registry.record("GET", "/metrics", "200", 0.01)
    val text = registry.renderPrometheusText()
    text should include("""http_request_duration_seconds_sum{method="GET",route="/metrics",status="200"}""")
    text should include("""http_request_duration_seconds_count{method="GET",route="/metrics",status="200"} 1""")

  // ─── HttpMetricsMiddleware.normalizeRoute ─────────────────────────────────

  "HttpMetricsMiddleware.normalizeRoute" should "normalize /health" in:
    HttpMetricsMiddleware.normalizeRoute("/health") shouldBe "/health"

  it should "normalize /metrics" in:
    HttpMetricsMiddleware.normalizeRoute("/metrics") shouldBe "/metrics"

  it should "normalize /sessions (collection)" in:
    HttpMetricsMiddleware.normalizeRoute("/sessions") shouldBe "/sessions"

  it should "normalize /sessions/import" in:
    HttpMetricsMiddleware.normalizeRoute("/sessions/import") shouldBe "/sessions/import"

  it should "normalize /sessions/import-notation" in:
    HttpMetricsMiddleware.normalizeRoute("/sessions/import-notation") shouldBe "/sessions/import-notation"

  it should "normalize /sessions/{id} (dynamic segment)" in:
    HttpMetricsMiddleware.normalizeRoute("/sessions/abc-123") shouldBe "/sessions/{sessionId}"

  it should "normalize /sessions/{id}/state" in:
    HttpMetricsMiddleware.normalizeRoute("/sessions/abc-123/state") shouldBe "/sessions/{sessionId}/state"

  it should "normalize /sessions/{id}/export" in:
    HttpMetricsMiddleware.normalizeRoute("/sessions/abc-123/export") shouldBe "/sessions/{sessionId}/export"

  it should "normalize /sessions/{id}/cancel" in:
    HttpMetricsMiddleware.normalizeRoute("/sessions/abc-123/cancel") shouldBe "/sessions/{sessionId}/cancel"

  it should "normalize /games/{id}" in:
    HttpMetricsMiddleware.normalizeRoute("/games/game-uuid-456") shouldBe "/games/{gameId}"

  it should "normalize /games/{id}/legal-moves" in:
    HttpMetricsMiddleware.normalizeRoute("/games/game-uuid-456/legal-moves") shouldBe "/games/{gameId}/legal-moves"

  it should "normalize /games/{id}/moves" in:
    HttpMetricsMiddleware.normalizeRoute("/games/game-uuid-456/moves") shouldBe "/games/{gameId}/moves"

  it should "normalize /games/{id}/resign" in:
    HttpMetricsMiddleware.normalizeRoute("/games/game-uuid-456/resign") shouldBe "/games/{gameId}/resign"

  it should "normalize /games/{id}/ai-move" in:
    HttpMetricsMiddleware.normalizeRoute("/games/game-uuid-456/ai-move") shouldBe "/games/{gameId}/ai-move"

  it should "normalize /games/{id}/notation/fen" in:
    HttpMetricsMiddleware.normalizeRoute("/games/game-uuid-456/notation/fen") shouldBe "/games/{gameId}/notation/fen"

  it should "normalize /games/{id}/notation/pgn" in:
    HttpMetricsMiddleware.normalizeRoute("/games/game-uuid-456/notation/pgn") shouldBe "/games/{gameId}/notation/pgn"

  it should "normalize /archive/games/{id}" in:
    HttpMetricsMiddleware.normalizeRoute("/archive/games/archived-uuid") shouldBe "/archive/games/{gameId}"

  it should "normalize /ops/history-outbox" in:
    HttpMetricsMiddleware.normalizeRoute("/ops/history-outbox") shouldBe "/ops/history-outbox"

  it should "normalize /ops/history-outbox/pending" in:
    HttpMetricsMiddleware.normalizeRoute("/ops/history-outbox/pending") shouldBe "/ops/history-outbox/pending"

  it should "normalize /ops/history-outbox/{id}" in:
    HttpMetricsMiddleware.normalizeRoute("/ops/history-outbox/some-id") shouldBe "/ops/history-outbox/{id}"

  it should "normalize /admin/... routes" in:
    HttpMetricsMiddleware.normalizeRoute("/admin/migrations") shouldBe "/admin/{...}"

  it should "return 'unknown' for unrecognized paths" in:
    HttpMetricsMiddleware.normalizeRoute("/completely/unknown/path/segments") shouldBe "unknown"
    HttpMetricsMiddleware.normalizeRoute("/") shouldBe "unknown"
