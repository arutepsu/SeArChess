package chess.adapter.event

import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.control.NonFatal

/** Best-effort local/dev bridge from Game Service terminal events to History.
 *
 *  This is intentionally not a durable event platform. It forwards only the
 *  terminal boundary events History can consume today and absorbs transport
 *  failures so gameplay is not coupled to History availability.
 */
class HistoryHttpEventPublisher(
  historyBaseUrl: String,
  timeoutMillis:  Int,
  sendJson:       (URI, String, Int) => Unit = HistoryHttpEventPublisher.defaultSend
) extends EventPublisher:

  private val endpoint: URI =
    URI.create(s"${historyBaseUrl.stripSuffix("/")}/events/game")

  override def publish(event: AppEvent): Unit =
    if isTerminalBoundaryEvent(event) then
      try
        AppEventSerializer.serialize(event).foreach(json =>
          sendJson(endpoint, json, timeoutMillis)
        )
      catch
        case NonFatal(e) =>
          System.err.println(s"[chess] History event forwarding failed: ${e.getMessage}")

  private def isTerminalBoundaryEvent(event: AppEvent): Boolean = event match
    case _: AppEvent.GameFinished     => true
    case _: AppEvent.GameResigned     => true
    case _: AppEvent.SessionCancelled => true
    case _                            => false

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
