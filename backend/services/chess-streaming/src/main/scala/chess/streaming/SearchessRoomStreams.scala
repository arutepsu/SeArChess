package chess.streaming

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{OverflowStrategy, QueueOfferResult}
import org.apache.pekko.stream.scaladsl.{BroadcastHub, Keep, Source, SourceQueueWithComplete}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/** A live Pekko room backed by one materialized Searchess DSL pipeline.
  *
  * Each room owns its own parser, validation state, game-processing state, envelope sequence, and
  * backpressure queue. Commands are submitted as raw Searchess DSL lines and events are broadcast to
  * every current subscriber.
  */
final class SearchessRoomStream private[streaming] (
    val roomId: String,
    private val queue: SourceQueueWithComplete[String],
    private val hubSource: Source[EventEnvelope, NotUsed]
):

  /** Submit one DSL command line to this room. */
  def submit(line: String): Future[QueueOfferResult] =
    queue.offer(line)

  /** Submit several DSL command lines in order. */
  def submitAll(lines: Iterable[String])(implicit system: ActorSystem): Future[Seq[QueueOfferResult]] =
    import system.dispatcher

    lines.foldLeft(Future.successful(Vector.empty[QueueOfferResult])) { (acc, line) =>
      acc.flatMap(results => submit(line).map(result => results :+ result))
    }

  /** Live unbatched event stream for the room. */
  def events: Source[EventEnvelope, NotUsed] =
    hubSource

  /** Live batched event stream for sinks that want the same batching strategy as the file demo. */
  def eventBatches: Source[Seq[EventEnvelope], NotUsed] =
    hubSource.via(SearchessReactiveStreams.batchBackpressureFlow)

  /** Complete the room input queue. Existing subscribers finish after buffered events drain. */
  def close(): Unit =
    queue.complete()

object SearchessRoomStream:
  private val RoomInputBufferSize = 32

  def create(roomId: String)(implicit system: ActorSystem): SearchessRoomStream =
    val (queue, hubSource) =
      Source
        .queue[String](RoomInputBufferSize, OverflowStrategy.backpressure)
        .via(SearchessReactiveStreams.parseDslFlow)
        .via(SearchessReactiveStreams.parsedValidationFlow)
        .via(SearchessReactiveStreams.processingInputFlow)
        .via(SearchessReactiveStreams.eventEnvelopeFlow)
        .via(SearchessReactiveStreams.backpressureBufferFlow)
        .via(SearchessReactiveStreams.failFastFlow)
        .via(SearchessReactiveStreams.recoverNonFatalFlow)
        .toMat(BroadcastHub.sink[EventEnvelope])(Keep.both)
        .run()

    new SearchessRoomStream(roomId, queue, hubSource)

/** Registry for live assignment rooms.
  *
  * The registry is intentionally local to `backend/services/chess-streaming`. It demonstrates Pekko room
  * orchestration without changing production `EventPublisher`, WebSocket, persistence, analytics,
  * or Kafka code.
  */
final class SearchessRoomRegistry(implicit system: ActorSystem):
  private val rooms = ConcurrentHashMap[String, SearchessRoomStream]()

  def getOrCreate(roomId: String): SearchessRoomStream =
    rooms.computeIfAbsent(roomId, id => SearchessRoomStream.create(id))

  def get(roomId: String): Option[SearchessRoomStream] =
    Option(rooms.get(roomId))

  def activeRoomIds: Set[String] =
    rooms.keySet().asScala.toSet

  def close(roomId: String): Boolean =
    Option(rooms.remove(roomId)) match
      case Some(room) =>
        room.close()
        true
      case None =>
        false

  def closeAll(): Unit =
    rooms.values().asScala.foreach(_.close())
    rooms.clear()
