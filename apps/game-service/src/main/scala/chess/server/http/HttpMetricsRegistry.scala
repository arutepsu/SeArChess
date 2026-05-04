package chess.server.http

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Thread-safe in-memory registry for HTTP request counters and duration histograms.
  *
  * Stores per-(method, route, status) counters and cumulative histogram buckets with no external
  * dependencies. All mutations use lock-free `AtomicLong` operations.
  *
  * Key encoding: `"METHOD|route|status"` — pipe is used as separator because route templates never
  * contain pipe characters.
  *
  * Histogram buckets follow the Prometheus convention: each bucket counts observations '''≤''' its
  * upper bound; the `+Inf` bucket always equals the total observation count. Bucket bounds are
  * defined in seconds: `[0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0]`.
  */
final class HttpMetricsRegistry:

  private[http] val bucketBounds: Array[Double] =
    Array(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0)

  private val requestCounts  = new ConcurrentHashMap[String, AtomicLong]()
  private val histoBuckets   = new ConcurrentHashMap[String, Array[AtomicLong]]()
  private val durationSumsNs = new ConcurrentHashMap[String, AtomicLong]()

  def record(method: String, route: String, status: String, durationSeconds: Double): Unit =
    val key = s"$method|$route|$status"

    val bkts = histoBuckets.computeIfAbsent(
      key,
      _ => Array.fill(bucketBounds.length + 1)(new AtomicLong(0L))
    )
    val firstExceeding = bucketBounds.indexWhere(durationSeconds <= _)
    if firstExceeding >= 0 then
      for j <- firstExceeding until bucketBounds.length do bkts(j).incrementAndGet()
    bkts(bucketBounds.length).incrementAndGet() // +Inf always

    durationSumsNs
      .computeIfAbsent(key, _ => new AtomicLong(0L))
      .addAndGet((durationSeconds * 1e9).toLong)

    // Populated last so renderKeys sees histoBuckets/durationSumsNs already present for this key
    requestCounts.computeIfAbsent(key, _ => new AtomicLong(0L)).incrementAndGet()

  def renderPrometheusText(): String =
    import scala.jdk.CollectionConverters.*

    val keys = requestCounts.keys().asScala.toList.sorted
    if keys.isEmpty then ""
    else renderKeys(keys)

  private def renderKeys(keys: List[String]): String =
    val sb = new java.lang.StringBuilder(2048)

    sb.append("# HELP http_requests_total Total HTTP requests\n")
    sb.append("# TYPE http_requests_total counter\n")
    for key <- keys do
      val (method, route, status) = parseKey(key)
      val count = requestCounts.get(key).get()
      sb.append(s"""http_requests_total{method="$method",route="$route",status="$status"} $count\n""")

    sb.append("# HELP http_request_duration_seconds HTTP request duration histogram\n")
    sb.append("# TYPE http_request_duration_seconds histogram\n")
    for key <- keys do
      val (method, route, status) = parseKey(key)
      val labels = s"""method="$method",route="$route",status="$status""""
      val bkts   = histoBuckets.get(key)
      for (bound, idx) <- bucketBounds.zipWithIndex do
        val le    = bound.toString
        val count = bkts(idx).get()
        sb.append(s"""http_request_duration_seconds_bucket{$labels,le="$le"} $count\n""")
      val infCount = bkts(bucketBounds.length).get()
      sb.append(s"""http_request_duration_seconds_bucket{$labels,le="+Inf"} $infCount\n""")
      val sumSeconds = durationSumsNs.get(key).get().toDouble / 1e9
      sb.append(s"""http_request_duration_seconds_sum{$labels} $sumSeconds\n""")
      sb.append(s"""http_request_duration_seconds_count{$labels} $infCount\n""")

    sb.toString

  private def parseKey(key: String): (String, String, String) =
    val parts = key.split('|')
    (parts(0), parts(1), parts(2))
