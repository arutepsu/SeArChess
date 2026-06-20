package chess.streaming

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.QueueOfferResult
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}

import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Tiny HTTP/WebSocket adapter for the Searchess reactive room registry. */
final class SearchessRoomHttpAdapter(registry: SearchessRoomRegistry)(implicit
    system: ActorSystem
):
  private given ExecutionContext = system.dispatcher

  def webSocketFlow(roomId: String): Flow[Message, Message, Any] =
    val room = registry.getOrCreate(roomId)

    val incoming: Sink[Message, Any] =
      Flow[Message]
        .mapAsync(1)(strictText)
        .map(parseClientCommand(roomId))
        .mapAsync(1) { command =>
          val targetRoom = registry.getOrCreate(command.roomId)
          targetRoom.submit(command.line).map(result => command -> result)
        }
        .to(Sink.foreach {
          case (command, QueueOfferResult.Enqueued) =>
            println(s"[searchess-room:${command.roomId}] accepted command: ${command.line}")
          case (command, other) =>
            System.err.println(s"[searchess-room:${command.roomId}] command was not enqueued: $other")
        })

    val connected =
      Source.single(TextMessage.Strict(ujson.write(ujson.Obj(
        "type" -> "connected",
        "roomId" -> roomId,
        "activeRooms" -> ujson.Arr.from(registry.activeRoomIds.toSeq.sorted.map(ujson.Str(_)))
      ))))

    val outgoing =
      room.events.map(envelope => TextMessage.Strict(envelopeJson(roomId, envelope)))

    Flow.fromSinkAndSource(incoming, connected.concat(outgoing))

  def route(indexHtmlPath: String): Route =
    pathSingleSlash {
      getFromFile(indexHtmlPath)
    } ~
      path("rooms") {
        get {
          completeJson(ujson.Obj(
            "rooms" -> ujson.Arr.from(registry.activeRoomIds.toSeq.sorted.map(ujson.Str(_)))
          ))
        }
      } ~
      path("rooms" / Segment / "commands") { roomId =>
        post {
          entity(as[String]) { body =>
            val command = parseClientCommand(roomId)(body)
            onSuccess(registry.getOrCreate(command.roomId).submit(command.line)) {
              case QueueOfferResult.Enqueued =>
                completeJson(ujson.Obj(
                  "status" -> "enqueued",
                  "roomId" -> command.roomId,
                  "line" -> command.line
                ))
              case other =>
                complete(
                  HttpResponse(
                    StatusCodes.ServiceUnavailable,
                    entity = HttpEntity(
                      ContentTypes.`application/json`,
                      ujson.write(ujson.Obj(
                        "status" -> "rejected",
                        "roomId" -> command.roomId,
                        "reason" -> other.toString
                      ))
                    )
                  )
                )
            }
          }
        }
      } ~
      path("rooms" / Segment / "events") { roomId =>
        handleWebSocketMessages(webSocketFlow(roomId))
      } ~
      path("game") {
        parameter("gameId".?) {
          case Some(roomId) => handleWebSocketMessages(webSocketFlow(roomId))
          case None         => handleWebSocketMessages(webSocketFlow("default"))
        }
      } ~
      path("game" / Segment) { roomId =>
        handleWebSocketMessages(webSocketFlow(roomId))
      }

  private def strictText(message: Message): Future[String] =
    message match
      case TextMessage.Strict(text) => Future.successful(text)
      case streamed: TextMessage =>
        streamed.textStream.runFold("")(_ + _)
      case _ =>
        Future.failed(IllegalArgumentException("only text WebSocket messages are supported"))

  private def parseClientCommand(defaultRoomId: String)(raw: String): RoomClientCommand =
    val trimmed = raw.trim
    if trimmed.startsWith("{") then
      try
        val json = ujson.read(trimmed).obj
        val roomId = json
          .get("roomId")
          .orElse(json.get("gameId"))
          .collect { case ujson.Str(value) if value.trim.nonEmpty => value.trim }
          .getOrElse(defaultRoomId)
        val line = json
          .get("line")
          .orElse(json.get("command"))
          .collect { case ujson.Str(value) if value.trim.nonEmpty => value.trim }
          .getOrElse(trimmed)
        RoomClientCommand(roomId, line)
      catch case NonFatal(_) => RoomClientCommand(defaultRoomId, trimmed)
    else RoomClientCommand(defaultRoomId, trimmed)

  private def envelopeJson(roomId: String, envelope: EventEnvelope): String =
    ujson.write(ujson.Obj(
      "type" -> "event",
      "roomId" -> roomId,
      "event" -> ujson.read(SearchessReactiveStreams.envelopeToJson(envelope))
    ))

  private def completeJson(value: ujson.Value): Route =
    complete(HttpEntity(ContentTypes.`application/json`, ujson.write(value)))

private final case class RoomClientCommand(roomId: String, line: String)

object ChessStreamingServerMain:

  def main(args: Array[String]): Unit =
    implicit val system: ActorSystem = ActorSystem("ChessStreamingServerSystem")
    implicit val ec: ExecutionContext = system.dispatcher

    val registry = SearchessRoomRegistry()
    val adapter = SearchessRoomHttpAdapter(registry)
    val indexHtmlPath = sys.env.getOrElse("INDEX_HTML_PATH", "backend/services/chess-streaming/index.html")
    val host = sys.env.getOrElse("STREAMING_HOST", "0.0.0.0")
    val port = sys.env.getOrElse("STREAMING_PORT", "8082").toInt

    val bindingFuture = Http().newServerAt(host, port).bind(adapter.route(indexHtmlPath))

    println("=========================================================")
    println("    SEARCHESS PEKKO ROOM STREAM SERVER STARTED          ")
    println(s"    HTTP:      http://$host:$port/                      ")
    println(s"    WebSocket: ws://$host:$port/rooms/<roomId>/events   ")
    println("=========================================================")
    println("Server is running. Press Ctrl+C to stop.")

    val keepAlive = Promise[Unit]()
    sys.addShutdownHook {
      registry.closeAll()
      keepAlive.trySuccess(())
    }

    try Await.result(keepAlive.future, Duration.Inf)
    catch case _: InterruptedException => ()

    bindingFuture
      .flatMap(_.unbind())
      .onComplete(_ => system.terminate())
