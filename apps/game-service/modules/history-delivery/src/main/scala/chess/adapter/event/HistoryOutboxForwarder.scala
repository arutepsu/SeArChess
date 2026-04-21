package chess.adapter.event

import chess.observability.StructuredLog

import java.net.URI
import scala.util.control.NonFatal

final case class HistoryOutboxDrainResult(
  readError: Option[String],
  attempted: Int,
  delivered: Int,
  failed: Int
)

/** Small background drain loop for the Game-to-History outbox.
 *
 *  Delivery is at-least-once. A successful HTTP 2xx response marks the row
 *  delivered. Before each HTTP send, the row is marked attempted. Any failure
 *  records `last_error` and leaves the row pending for the next loop or the
 *  next Game Service restart.
 */
class HistoryOutboxForwarder(
  outbox:             HistoryEventOutbox,
  historyBaseUrl:     String,
  timeoutMillis:      Int,
  pollIntervalMillis: Int = 1000,
  batchSize:          Int = 25,
  sendJson:           (URI, String, Int) => Unit = HistoryHttpEventPublisher.defaultSend
):

  private val endpoint: URI =
    URI.create(s"${historyBaseUrl.stripSuffix("/")}${GameHistoryIngestionContract.GameEventsPath}")

  @volatile private var running = false
  private var worker: Option[Thread] = None

  def start(): Unit = synchronized:
    if !running then
      running = true
      val t = Thread(() => loop(), "history-outbox-forwarder")
      t.setDaemon(true)
      worker = Some(t)
      t.start()

  def stop(): Unit = synchronized:
    running = false
    worker.foreach(_.interrupt())
    worker = None

  def runOnce(): HistoryOutboxDrainResult =
    outbox.pending(batchSize) match
      case Left(err) =>
        StructuredLog.warn("game-service", "history_outbox_read_failed", "error" -> err)
        HistoryOutboxDrainResult(readError = Some(err), attempted = 0, delivered = 0, failed = 0)
      case Right(entries) =>
        val results = entries.map(deliver)
        HistoryOutboxDrainResult(
          readError = None,
          attempted = results.count(_ != DeliveryResult.AttemptNotRecorded),
          delivered = results.count(_ == DeliveryResult.Delivered),
          failed = results.count(_ != DeliveryResult.Delivered)
        )

  private def loop(): Unit =
    while running do
      runOnce()
      try Thread.sleep(pollIntervalMillis.toLong)
      catch case _: InterruptedException => ()

  private def deliver(entry: HistoryOutboxEntry): DeliveryResult =
    outbox.markAttempted(entry.id) match
      case Left(err) =>
        logWarn("history_outbox_mark_attempted_failed", entry, "error" -> err)
        DeliveryResult.AttemptNotRecorded
      case Right(_) =>
        logInfo("history_outbox_delivery_attempted", entry, "attempt" -> (entry.attempts + 1), "endpoint" -> endpoint.toString)
        try
          sendJson(endpoint, entry.payloadJson, timeoutMillis)
          outbox.markDelivered(entry.id) match
            case Right(_) =>
              logInfo("history_outbox_delivery_succeeded", entry, "attempt" -> (entry.attempts + 1))
              DeliveryResult.Delivered
            case Left(err) =>
              logWarn("history_outbox_mark_delivered_failed", entry, "attempt" -> (entry.attempts + 1), "error" -> err)
              outbox.markFailed(entry.id, s"mark-delivered failed: $err").left.foreach { markErr =>
                logWarn("history_outbox_mark_failed_failed", entry, "attempt" -> (entry.attempts + 1), "error" -> markErr)
              }
              DeliveryResult.Failed
        catch
          case NonFatal(e) =>
            val message = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
            logWarn("history_outbox_delivery_failed", entry, "attempt" -> (entry.attempts + 1), "error" -> message)
            outbox.markFailed(entry.id, message).left.foreach { err =>
              logWarn("history_outbox_mark_failed_failed", entry, "attempt" -> (entry.attempts + 1), "error" -> err)
            }
            DeliveryResult.Failed

  private enum DeliveryResult:
    case Delivered
    case Failed
    case AttemptNotRecorded

  private def logInfo(event: String, entry: HistoryOutboxEntry, fields: (String, Any)*): Unit =
    StructuredLog.info("game-service", event, (entryFields(entry) ++ fields)*)

  private def logWarn(event: String, entry: HistoryOutboxEntry, fields: (String, Any)*): Unit =
    StructuredLog.warn("game-service", event, (entryFields(entry) ++ fields)*)

  private def entryFields(entry: HistoryOutboxEntry): Seq[(String, Any)] =
    Seq(
      "outboxId" -> entry.id,
      "eventType" -> entry.eventType,
      "gameId" -> entry.gameId,
      "sessionId" -> entry.sessionId,
      "attempts" -> entry.attempts
    )
