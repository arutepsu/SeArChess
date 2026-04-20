package chess.adapter.event

import java.net.URI
import scala.util.control.NonFatal

/** Small background drain loop for the Game-to-History outbox.
 *
 *  Delivery is at-least-once. A successful HTTP 2xx response marks the row
 *  delivered. Any failure increments attempts and leaves the row pending for
 *  the next loop or the next Game Service restart.
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
    URI.create(s"${historyBaseUrl.stripSuffix("/")}/events/game")

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

  def runOnce(): Unit =
    outbox.pending(batchSize) match
      case Left(err) =>
        System.err.println(s"[chess] History outbox read failed: $err")
      case Right(entries) =>
        entries.foreach(deliver)

  private def loop(): Unit =
    while running do
      runOnce()
      try Thread.sleep(pollIntervalMillis.toLong)
      catch case _: InterruptedException => ()

  private def deliver(entry: HistoryOutboxEntry): Unit =
    try
      sendJson(endpoint, entry.payloadJson, timeoutMillis)
      outbox.markDelivered(entry.id).left.foreach { err =>
        System.err.println(s"[chess] History outbox mark-delivered failed for ${entry.id}: $err")
      }
    catch
      case NonFatal(e) =>
        val message = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
        outbox.markFailed(entry.id, message).left.foreach { err =>
          System.err.println(s"[chess] History outbox mark-failed failed for ${entry.id}: $err")
        }
