package chess.adapter.http4s

import java.util.concurrent.atomic.AtomicLong

/** Thread-safe in-memory registry for Searchess domain-level Prometheus metrics.
  *
  * Tracks operation-level counters and duration histograms for the four main gameplay operations:
  * session creation, legal-move generation, move submission, and session-state fetch. All mutation
  * uses lock-free [[AtomicLong]] operations.
  *
  * All metrics are emitted on every [[renderPrometheusText]] call, even when their value is zero,
  * so Prometheus and Grafana can discover metric families immediately after startup without waiting
  * for the first observation.
  *
  * Histogram bucket bounds (seconds): `0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0`
  * — the same scale as [[chess.server.http.HttpMetricsRegistry]].
  *
  * No session IDs, game IDs, player IDs, or run IDs are used as labels.
  */
final class DomainMetricsRegistry:

  private[http4s] val bucketBounds: Array[Double] =
    Array(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0)

  private val sessionsCreated     = new AtomicLong(0L)
  private val gamesCreated        = new AtomicLong(0L)
  private val legalMovesRequested = new AtomicLong(0L)
  private val movesSubmitted      = new AtomicLong(0L)

  private val legalMovesBuckets = Array.fill(bucketBounds.length + 1)(new AtomicLong(0L))
  private val legalMovesSumNs   = new AtomicLong(0L)

  private val submitMoveBuckets = Array.fill(bucketBounds.length + 1)(new AtomicLong(0L))
  private val submitMoveSumNs   = new AtomicLong(0L)

  private val fetchStateBuckets = Array.fill(bucketBounds.length + 1)(new AtomicLong(0L))
  private val fetchStateSumNs   = new AtomicLong(0L)

  /** Record a successful session and game creation.
    *
    * Increments both counters together because a session and its initial game are always created as
    * a pair. Only called on a 201 response — validation failures do not increment either counter.
    */
  def recordSessionCreated(): Unit =
    sessionsCreated.incrementAndGet()
    gamesCreated.incrementAndGet()

  /** Record a legal-move generation request. Called for every request whose game ID is a valid
    * UUID, regardless of whether the game was found.
    */
  def recordLegalMovesRequested(durationSeconds: Double): Unit =
    legalMovesRequested.incrementAndGet()
    recordHistogram(legalMovesBuckets, legalMovesSumNs, durationSeconds)

  /** Record a move submission. Called after the request body, move, and controller have been
    * parsed — only requests that actually reach the game service are counted.
    */
  def recordMoveSubmitted(durationSeconds: Double): Unit =
    movesSubmitted.incrementAndGet()
    recordHistogram(submitMoveBuckets, submitMoveSumNs, durationSeconds)

  /** Record a session-state fetch. Called for every request whose session ID is a valid UUID,
    * regardless of whether the session was found.
    */
  def recordFetchState(durationSeconds: Double): Unit =
    recordHistogram(fetchStateBuckets, fetchStateSumNs, durationSeconds)

  /** Render all domain metrics in Prometheus text format (version 0.0.4). */
  def renderPrometheusText(): String =
    val sb = new java.lang.StringBuilder(1024)

    appendCounter(sb, "searchess_sessions_created_total",
      "Total chess sessions created", sessionsCreated.get())
    appendCounter(sb, "searchess_games_created_total",
      "Total chess games created", gamesCreated.get())
    appendCounter(sb, "searchess_legal_moves_requested_total",
      "Total legal-move generation requests", legalMovesRequested.get())
    appendCounter(sb, "searchess_moves_submitted_total",
      "Total moves submitted to the game service", movesSubmitted.get())

    appendHistogram(sb,
      "searchess_legal_move_generation_duration_seconds",
      "Legal move generation latency in seconds",
      legalMovesBuckets, legalMovesSumNs)
    appendHistogram(sb,
      "searchess_submit_move_duration_seconds",
      "Move submission latency in seconds",
      submitMoveBuckets, submitMoveSumNs)
    appendHistogram(sb,
      "searchess_fetch_state_duration_seconds",
      "Session state fetch latency in seconds",
      fetchStateBuckets, fetchStateSumNs)

    sb.toString

  private[http4s] def recordHistogram(
      buckets: Array[AtomicLong],
      sumNs: AtomicLong,
      durationSeconds: Double
  ): Unit =
    val firstExceeding = bucketBounds.indexWhere(durationSeconds <= _)
    if firstExceeding >= 0 then
      for j <- firstExceeding until bucketBounds.length do buckets(j).incrementAndGet()
    buckets(bucketBounds.length).incrementAndGet()
    sumNs.addAndGet((durationSeconds * 1e9).toLong)

  private def appendCounter(
      sb: java.lang.StringBuilder,
      name: String,
      help: String,
      value: Long
  ): Unit =
    sb.append("# HELP ").append(name).append(' ').append(help).append('\n')
    sb.append("# TYPE ").append(name).append(" counter\n")
    sb.append(name).append(' ').append(value).append('\n')

  private def appendHistogram(
      sb: java.lang.StringBuilder,
      name: String,
      help: String,
      buckets: Array[AtomicLong],
      sumNs: AtomicLong
  ): Unit =
    sb.append("# HELP ").append(name).append(' ').append(help).append('\n')
    sb.append("# TYPE ").append(name).append(" histogram\n")
    for (bound, idx) <- bucketBounds.zipWithIndex do
      sb.append(s"""${name}_bucket{le="${bound}"} ${buckets(idx).get()}\n""")
    val infCount = buckets(bucketBounds.length).get()
    sb.append(s"""${name}_bucket{le="+Inf"} $infCount\n""")
    val sumSeconds = sumNs.get().toDouble / 1e9
    sb.append(s"""${name}_sum $sumSeconds\n""")
    sb.append(s"""${name}_count $infCount\n""")
