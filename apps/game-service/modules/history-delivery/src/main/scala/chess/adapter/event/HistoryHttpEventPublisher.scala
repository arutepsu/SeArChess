package chess.adapter.event

import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher
import chess.observability.StructuredLog
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
    URI.create(s"${historyBaseUrl.stripSuffix("/")}${GameHistoryIngestionContract.GameEventsPath}")

  override def publish(event: AppEvent): Unit =
    if DurableHistoryEventPublisher.isTerminalBoundaryEvent(event) then
      try
        AppEventSerializer.serialize(event).foreach(json =>
          StructuredLog.info(
            "game-service",
            "history_direct_delivery_attempted",
            "eventType" -> DurableHistoryEventPublisher.eventName(event),
            "gameId" -> event.gameId.value.toString,
            "sessionId" -> event.sessionId.value.toString,
            "endpoint" -> endpoint.toString
          )
          sendJson(endpoint, json, timeoutMillis)
          StructuredLog.info(
            "game-service",
            "history_direct_delivery_succeeded",
            "eventType" -> DurableHistoryEventPublisher.eventName(event),
            "gameId" -> event.gameId.value.toString,
            "sessionId" -> event.sessionId.value.toString
          )
        )
      catch
        case NonFatal(e) =>
          StructuredLog.warn(
            "game-service",
            "history_direct_delivery_failed",
            "eventType" -> DurableHistoryEventPublisher.eventName(event),
            "gameId" -> event.gameId.value.toString,
            "sessionId" -> event.sessionId.value.toString,
            "error" -> e.getMessage
          )

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
