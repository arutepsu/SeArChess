package chess.streaming

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{OverflowStrategy, QueueOfferResult}
import org.apache.pekko.stream.scaladsl.{BroadcastHub, Flow, Keep, Sink, Source, SourceQueueWithComplete}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

final case class RoomStreamSettings(
    recoverNonFatal: Boolean = true,
    failFast: Boolean = false,
    throttlePerElement: Option[FiniteDuration] = None
)

/** A live Pekko room backed by one materialized Searchess DSL pipeline.
  *
  * Each room owns its own parser, validation state, game-processing state, envelope sequence, and
  * backpressure queue. Commands are submitted as raw Searchess DSL lines and events are broadcast to
  * every current subscriber.
  */
final class SearchessRoomStream private[streaming] (
    val roomId: String,
    private val queue: SourceQueueWithComplete[String],
    private val hubSource: Source[EventEnvelope, NotUsed],
    val summary: Future[StreamSummary]
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

  /** Live error/dead-letter stream for parse, validation, move, or recovered stream errors. */
  def deadLetters: Source[EventEnvelope, NotUsed] =
    hubSource.filter(SearchessRoomStream.isDeadLetter)

  /** Complete the room input queue. Existing subscribers finish after buffered events drain. */
  def close(): Unit =
    queue.complete()

object SearchessRoomStream:
  private val RoomInputBufferSize = 32

  def create(
      roomId: String,
      settings: RoomStreamSettings = RoomStreamSettings()
  )(implicit system: ActorSystem): SearchessRoomStream =
    val basePipeline =
      Source
        .queue[String](RoomInputBufferSize, OverflowStrategy.backpressure)
        .via(SearchessReactiveStreams.parseDslFlow)
        .via(SearchessReactiveStreams.parsedValidationFlow)
        .via(SearchessReactiveStreams.processingInputFlow)
        .via(SearchessReactiveStreams.eventEnvelopeFlow)
        .via(SearchessReactiveStreams.backpressureBufferFlow)

    val configuredPipeline =
      basePipeline
        .via(throttleFlow(settings))
        .via(errorModeFlow(settings))

    val ((queue, summary), hubSource) =
      configuredPipeline
        .alsoToMat(summarySink)(Keep.both)
        .toMat(BroadcastHub.sink[EventEnvelope])(Keep.both)
        .run()

    new SearchessRoomStream(roomId, queue, hubSource, summary)

  def isDeadLetter(envelope: EventEnvelope): Boolean =
    envelope.eventType match
      case "ParseFailed" | "ValidationFailed" | "MoveRejected" | "StreamRecovered" => true
      case _ => false

  private def throttleFlow(settings: RoomStreamSettings): Flow[EventEnvelope, EventEnvelope, NotUsed] =
    settings.throttlePerElement match
      case Some(interval) => Flow[EventEnvelope].throttle(1, interval)
      case None => Flow[EventEnvelope]

  private def errorModeFlow(settings: RoomStreamSettings): Flow[EventEnvelope, EventEnvelope, NotUsed] =
    if settings.failFast then SearchessReactiveStreams.failFastFlow
    else if settings.recoverNonFatal then SearchessReactiveStreams.recoverNonFatalFlow
    else Flow[EventEnvelope]

  private val summarySink: Sink[EventEnvelope, Future[StreamSummary]] =
    Sink.fold(StreamSummary.empty) { (summary, envelope) =>
      mergeSummaries(summary, StreamSummary.fromEnvelopes(List(envelope)))
    }

  private def mergeSummaries(left: StreamSummary, right: StreamSummary): StreamSummary =
    StreamSummary(
      totalLines = left.totalLines + right.totalLines,
      parsedCommands = left.parsedCommands + right.parsedCommands,
      totalEvents = left.totalEvents + right.totalEvents,
      acceptedMoves = left.acceptedMoves + right.acceptedMoves,
      rejectedMoves = left.rejectedMoves + right.rejectedMoves,
      parseFailures = left.parseFailures + right.parseFailures,
      validationFailures = left.validationFailures + right.validationFailures,
      finishedGames = left.finishedGames + right.finishedGames
    )

/** Registry for live assignment rooms.
  *
  * The registry is intentionally local to `apps/chess-streaming`. It demonstrates Pekko room
  * orchestration without changing production `EventPublisher`, WebSocket, persistence, analytics,
  * or Kafka code.
  */
final class SearchessRoomRegistry(implicit system: ActorSystem):
  private val rooms = ConcurrentHashMap[String, SearchessRoomStream]()
  private val closedSummaries = ConcurrentHashMap[String, StreamSummary]()

  def getOrCreate(
      roomId: String,
      settings: RoomStreamSettings = RoomStreamSettings()
  ): SearchessRoomStream =
    rooms.computeIfAbsent(roomId, id => SearchessRoomStream.create(id, settings))

  def get(roomId: String): Option[SearchessRoomStream] =
    Option(rooms.get(roomId))

  def getClosedSummary(roomId: String): Option[StreamSummary] =
    Option(closedSummaries.get(roomId))

  def activeRoomIds: Set[String] =
    rooms.keySet().asScala.toSet

  def close(roomId: String): Boolean =
    Option(rooms.remove(roomId)) match
      case Some(room) =>
        room.close()
        room.summary.foreach(summary => closedSummaries.put(roomId, summary))(using system.dispatcher)
        true
      case None =>
        false

  def reset(
      roomId: String,
      settings: RoomStreamSettings = RoomStreamSettings()
  ): SearchessRoomStream =
    Option(rooms.remove(roomId)).foreach(_.close())
    closedSummaries.remove(roomId)
    val room = SearchessRoomStream.create(roomId, settings)
    rooms.put(roomId, room)
    room

  def closeAndSummarize(roomId: String): Future[Option[StreamSummary]] =
    Option(rooms.remove(roomId)) match
      case Some(room) =>
        room.close()
        room.summary.map { summary =>
          closedSummaries.put(roomId, summary)
          Some(summary)
        }(using system.dispatcher)
      case None =>
        Future.successful(getClosedSummary(roomId))

  def closeAll(): Unit =
    rooms.values().asScala.foreach(_.close())
    rooms.clear()
