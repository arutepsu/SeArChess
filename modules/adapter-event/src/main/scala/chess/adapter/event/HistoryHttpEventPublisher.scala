package chess.adapter.event

import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.control.NonFatal

/** Best-effort direct HTTP bridge from Game Service terminal events to History.
 *
 *  SQLite Game Service deployments should use [[DurableHistoryEventPublisher]]
 *  plus [[HistoryOutboxForwarder]]. This class remains as the small fallback
 *  for non-durable in-memory development mode.
 */
class HistoryHttpEventPublisher(
  historyBaseUrl: String,
  timeoutMillis:  Int,
  sendJson:       (URI, String, Int) => Unit = HistoryHttpEventPublisher.defaultSend
) extends EventPublisher:

  private val endpoint: URI =
    URI.create(s"${historyBaseUrl.stripSuffix("/")}/events/game")

  override def publish(event: AppEvent): Unit =
    if DurableHistoryEventPublisher.isTerminalBoundaryEvent(event) then
      try
        AppEventSerializer.serialize(event).foreach(json =>
          sendJson(endpoint, json, timeoutMillis)
        )
      catch
        case NonFatal(e) =>
          System.err.println(s"[chess] History event forwarding failed: ${e.getMessage}")

object HistoryHttpEventPublisher:

  private val Client: HttpClient = HttpClient.newHttpClient()

  def defaultSend(endpoint: URI, json: String, timeoutMillis: Int): Unit =
    val request = HttpRequest
      .newBuilder(endpoint)
      .timeout(Duration.ofMillis(timeoutMillis.toLong))
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(json))
      .build()

    val response = Client.send(request, HttpResponse.BodyHandlers.discarding())
    if response.statusCode() < 200 || response.statusCode() >= 300 then
      throw RuntimeException(s"History Service returned HTTP ${response.statusCode()}")
