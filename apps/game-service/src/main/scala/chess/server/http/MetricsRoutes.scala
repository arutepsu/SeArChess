package chess.server.http

import cats.effect.IO
import org.http4s.{Charset, HttpRoutes, MediaType}
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

import java.lang.management.ManagementFactory
import scala.jdk.CollectionConverters.*

/** Prometheus-compatible metrics endpoint for operational observability.
  *
  * Exposes JVM memory, GC, thread, and process-uptime metrics together with HTTP request counters
  * and duration histograms in the Prometheus text exposition format (version 0.0.4). This route is
  * intentionally mounted on the internal HTTP surface (port 8080) and is NOT routed through Envoy,
  * so Prometheus can scrape it directly.
  *
  * ===Endpoint===
  * {{{
  *   GET /metrics -> 200 OK
  *   Content-Type: text/plain; charset=utf-8
  *
  *   # HELP jvm_memory_heap_used_bytes ...
  *   jvm_memory_heap_used_bytes <bytes>
  *   ...
  *   # HELP http_requests_total ...
  *   http_requests_total{method="GET",route="/sessions",status="200"} 42
  *   ...
  * }}}
  *
  * No application secrets, request/response bodies, or user data are exposed. JVM values are drawn
  * from the JVM management API; HTTP metrics from the in-process [[HttpMetricsRegistry]].
  */
object MetricsRoutes:

  private val textPlainUtf8 = `Content-Type`(MediaType.text.plain, Charset.`UTF-8`)

  def routes(registry: HttpMetricsRegistry): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "metrics" =>
        IO(buildMetricsText(registry)).flatMap(body => Ok(body).map(_.withContentType(textPlainUtf8)))

  private def buildMetricsText(registry: HttpMetricsRegistry): String =
    val sb = new java.lang.StringBuilder(2048)

    appendJvmMetrics(sb)
    val httpText = registry.renderPrometheusText()
    if httpText.nonEmpty then sb.append(httpText)

    sb.toString

  private def appendJvmMetrics(sb: java.lang.StringBuilder): Unit =
    val heap = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage
    val rt   = ManagementFactory.getRuntimeMXBean
    val thr  = ManagementFactory.getThreadMXBean

    def gauge(name: String, help: String, value: Long): Unit =
      sb.append("# HELP ").append(name).append(' ').append(help).append('\n')
      sb.append("# TYPE ").append(name).append(" gauge\n")
      sb.append(name).append(' ').append(value).append('\n')

    gauge("jvm_memory_heap_used_bytes",      "Current heap memory used in bytes",   heap.getUsed)
    gauge("jvm_memory_heap_committed_bytes", "Committed heap memory in bytes",      heap.getCommitted)
    gauge("jvm_memory_heap_max_bytes",       "Maximum heap memory in bytes",        heap.getMax)
    gauge("jvm_threads_current",             "Current number of live threads",      thr.getThreadCount.toLong)
    gauge("process_uptime_seconds",          "Process uptime in seconds",           rt.getUptime / 1000)

    val gcs = ManagementFactory.getGarbageCollectorMXBeans.asScala.toList
    if gcs.nonEmpty then
      sb.append("# HELP jvm_gc_collection_count_total Total number of GC collections\n")
      sb.append("# TYPE jvm_gc_collection_count_total counter\n")
      for gc <- gcs do
        val name  = gc.getName.replace("\"", "\\\"")
        val count = math.max(0L, gc.getCollectionCount)
        sb.append("jvm_gc_collection_count_total{gc=\"").append(name).append("\"} ").append(count).append('\n')

      sb.append("# HELP jvm_gc_collection_seconds_total Total time spent in GC in seconds\n")
      sb.append("# TYPE jvm_gc_collection_seconds_total counter\n")
      for gc <- gcs do
        val name    = gc.getName.replace("\"", "\\\"")
        val seconds = math.max(0L, gc.getCollectionTime).toDouble / 1000.0
        sb.append("jvm_gc_collection_seconds_total{gc=\"").append(name).append("\"} ").append(seconds).append('\n')
