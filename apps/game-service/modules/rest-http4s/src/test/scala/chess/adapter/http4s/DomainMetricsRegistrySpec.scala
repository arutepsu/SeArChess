package chess.adapter.http4s

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DomainMetricsRegistrySpec extends AnyFlatSpec with Matchers:

  // ─── initial state ────────────────────────────────────────────────────────

  "DomainMetricsRegistry" should "include all expected metric names in renderPrometheusText from the start" in:
    val reg  = new DomainMetricsRegistry
    val text = reg.renderPrometheusText()
    text should include("searchess_sessions_created_total")
    text should include("searchess_games_created_total")
    text should include("searchess_legal_moves_requested_total")
    text should include("searchess_moves_submitted_total")
    text should include("searchess_legal_move_generation_duration_seconds")
    text should include("searchess_submit_move_duration_seconds")
    text should include("searchess_fetch_state_duration_seconds")

  it should "emit zero-valued counters before any events are recorded" in:
    val reg  = new DomainMetricsRegistry
    val text = reg.renderPrometheusText()
    text should include("searchess_sessions_created_total 0")
    text should include("searchess_games_created_total 0")
    text should include("searchess_legal_moves_requested_total 0")
    text should include("searchess_moves_submitted_total 0")

  it should "emit zero-valued +Inf bucket before any histograms are recorded" in:
    val reg  = new DomainMetricsRegistry
    val text = reg.renderPrometheusText()
    text should include("""searchess_legal_move_generation_duration_seconds_bucket{le="+Inf"} 0""")
    text should include("""searchess_submit_move_duration_seconds_bucket{le="+Inf"} 0""")
    text should include("""searchess_fetch_state_duration_seconds_bucket{le="+Inf"} 0""")

  // ─── recordSessionCreated ────────────────────────────────────────────────

  it should "increment sessions_created_total after recordSessionCreated" in:
    val reg = new DomainMetricsRegistry
    reg.recordSessionCreated()
    reg.renderPrometheusText() should include("searchess_sessions_created_total 1")

  it should "increment games_created_total together with sessions_created_total" in:
    val reg = new DomainMetricsRegistry
    reg.recordSessionCreated()
    reg.recordSessionCreated()
    val text = reg.renderPrometheusText()
    text should include("searchess_sessions_created_total 2")
    text should include("searchess_games_created_total 2")

  // ─── recordLegalMovesRequested ───────────────────────────────────────────

  it should "increment legal_moves_requested_total after recordLegalMovesRequested" in:
    val reg = new DomainMetricsRegistry
    reg.recordLegalMovesRequested(0.003)
    reg.renderPrometheusText() should include("searchess_legal_moves_requested_total 1")

  it should "record legal move duration in the +Inf bucket" in:
    val reg = new DomainMetricsRegistry
    reg.recordLegalMovesRequested(0.003)
    reg.renderPrometheusText() should include(
      """searchess_legal_move_generation_duration_seconds_bucket{le="+Inf"} 1"""
    )

  it should "place a fast legal move observation in the correct bucket" in:
    val reg = new DomainMetricsRegistry
    reg.recordLegalMovesRequested(0.003) // ≤ 0.005
    val text = reg.renderPrometheusText()
    text should include("""searchess_legal_move_generation_duration_seconds_bucket{le="0.005"} 1""")
    text should include("""searchess_legal_move_generation_duration_seconds_bucket{le="1.0"} 1""")

  it should "not count a slow legal move observation in fast buckets" in:
    val reg = new DomainMetricsRegistry
    reg.recordLegalMovesRequested(1.5) // between 1.0 and 2.5
    val text = reg.renderPrometheusText()
    text should include("""searchess_legal_move_generation_duration_seconds_bucket{le="1.0"} 0""")
    text should include("""searchess_legal_move_generation_duration_seconds_bucket{le="2.5"} 1""")

  // ─── recordMoveSubmitted ─────────────────────────────────────────────────

  it should "increment moves_submitted_total after recordMoveSubmitted" in:
    val reg = new DomainMetricsRegistry
    reg.recordMoveSubmitted(0.005)
    reg.recordMoveSubmitted(0.010)
    reg.renderPrometheusText() should include("searchess_moves_submitted_total 2")

  it should "record submit move duration in the +Inf bucket" in:
    val reg = new DomainMetricsRegistry
    reg.recordMoveSubmitted(0.020)
    reg.renderPrometheusText() should include(
      """searchess_submit_move_duration_seconds_bucket{le="+Inf"} 1"""
    )

  // ─── recordFetchState ────────────────────────────────────────────────────

  it should "record fetch state duration without incrementing any counter" in:
    val reg = new DomainMetricsRegistry
    reg.recordFetchState(0.002)
    val text = reg.renderPrometheusText()
    text should include("""searchess_fetch_state_duration_seconds_bucket{le="+Inf"} 1""")
    text should include("searchess_sessions_created_total 0")

  // ─── label safety ────────────────────────────────────────────────────────

  it should "not include sessionId in metric output" in:
    val reg = new DomainMetricsRegistry
    reg.recordSessionCreated()
    reg.renderPrometheusText() should not include "sessionId"

  it should "not include gameId in metric output" in:
    val reg = new DomainMetricsRegistry
    reg.recordLegalMovesRequested(0.001)
    reg.renderPrometheusText() should not include "gameId"

  it should "not include runId in metric output" in:
    val reg = new DomainMetricsRegistry
    reg.recordMoveSubmitted(0.010)
    reg.renderPrometheusText() should not include "runId"

  // ─── HELP and TYPE lines ─────────────────────────────────────────────────

  it should "include HELP and TYPE lines for counters" in:
    val reg  = new DomainMetricsRegistry
    val text = reg.renderPrometheusText()
    text should include("# HELP searchess_sessions_created_total")
    text should include("# TYPE searchess_sessions_created_total counter")
    text should include("# HELP searchess_moves_submitted_total")
    text should include("# TYPE searchess_moves_submitted_total counter")

  it should "include HELP and TYPE lines for histograms" in:
    val reg  = new DomainMetricsRegistry
    val text = reg.renderPrometheusText()
    text should include("# HELP searchess_legal_move_generation_duration_seconds")
    text should include("# TYPE searchess_legal_move_generation_duration_seconds histogram")
    text should include("# HELP searchess_submit_move_duration_seconds")
    text should include("# TYPE searchess_submit_move_duration_seconds histogram")
    text should include("# HELP searchess_fetch_state_duration_seconds")
    text should include("# TYPE searchess_fetch_state_duration_seconds histogram")

  // ─── histogram sum and count ─────────────────────────────────────────────

  it should "include _sum and _count lines for histograms" in:
    val reg = new DomainMetricsRegistry
    reg.recordLegalMovesRequested(0.050)
    val text = reg.renderPrometheusText()
    text should include("searchess_legal_move_generation_duration_seconds_sum")
    text should include("searchess_legal_move_generation_duration_seconds_count 1")

  it should "accumulate _sum across multiple observations" in:
    val reg = new DomainMetricsRegistry
    reg.recordMoveSubmitted(0.1)
    reg.recordMoveSubmitted(0.2)
    val text = reg.renderPrometheusText()
    text should include("searchess_submit_move_duration_seconds_count 2")
