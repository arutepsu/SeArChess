package chess.streaming

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub, Sink, Source}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.ExecutionContext

class GameRoom(val gameId: String)(implicit system: ActorSystem) {
  implicit val ec: ExecutionContext = system.dispatcher

  @volatile var currentState: GameState = GameState.initial

  val (hubSink, hubSource) = MergeHub.source[Either[Throwable, Move]]
    .via(ChessStreamingEngine.validatorFlow)
    .map { result =>
      result.foreach { state =>
        currentState = state
      }
      result
    }
    .toMat(BroadcastHub.sink[Either[String, GameState]])(Keep.both)
    .run()
}

object GameRoomRegistry {
  private val rooms = new ConcurrentHashMap[String, GameRoom]()

  def getOrCreate(gameId: String)(implicit system: ActorSystem): GameRoom = {
    rooms.computeIfAbsent(gameId, id => new GameRoom(id))
  }
}

object ChessStreamingServerMain {

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("ChessStreamingServerSystem")
    implicit val ec: ExecutionContext = system.dispatcher

    // WebSocket handler logic per connection
    def webSocketFlow(gameId: String): Flow[Message, Message, Any] = {
      val room = GameRoomRegistry.getOrCreate(gameId)

      // Ingress: process incoming client messages and route moves to the room's Sink
      val incomingSink: Sink[Message, Any] = Flow[Message]
        .collect {
          case TextMessage.Strict(text) =>
            try {
              val json = ujson.read(text)
              val targetGameId = json.obj.get("gameId").map(_.str).getOrElse(gameId)
              val moveStr = json("move").str
              (targetGameId, moveStr)
            } catch {
              case _: Exception => (gameId, text)
            }
        }
        .map { case (gId, moveStr) =>
          val parsed = ChessStreamingEngine.parseMove(moveStr)
          (gId, parsed)
        }
        .to(Sink.foreach { case (gId, parsedMove) =>
          val targetRoom = GameRoomRegistry.getOrCreate(gId)
          Source.single(parsedMove).runWith(targetRoom.hubSink)
        })

      // Egress: serialize room events to TextMessage
      val outgoingFlow: Flow[Either[String, GameState], Message, Any] = Flow[Either[String, GameState]].map {
        case Right(state) =>
          val boardJson = ujson.Obj.from(state.board.map { case (square, piece) =>
            square -> ujson.Str(piece)
          })
          val movesJson = ujson.Arr(state.moveHistory.map(m => ujson.Str(s"${m.from}-${m.to}"))*)
          val response = ujson.Obj(
            "status" -> "success",
            "activeColor" -> state.activeColor,
            "board" -> boardJson,
            "moves" -> movesJson
          )
          TextMessage.Strict(ujson.write(response))

        case Left(error) =>
          val response = ujson.Obj(
            "status" -> "error",
            "message" -> error
          )
          TextMessage.Strict(ujson.write(response))
      }

      // Prepend current state on connect, then stream live updates from BroadcastHub
      val initialSource = Source.single(Right(room.currentState))
      val liveSource = room.hubSource
      val outgoingSource = initialSource.concat(liveSource)

      Flow.fromSinkAndSource(incomingSink, outgoingSource.via(outgoingFlow))
    }

    val route =
      path("game") {
        parameter("gameId".?) {
          case Some(gameId) => handleWebSocketMessages(webSocketFlow(gameId))
          case None         => handleWebSocketMessages(webSocketFlow("default"))
        }
      } ~
      path("game" / Segment) { gameId =>
        handleWebSocketMessages(webSocketFlow(gameId))
      }

    val host = "localhost"
    val port = 8080

    val bindingFuture = Http().newServerAt(host, port).bind(route)

    import scala.concurrent.Promise
    import scala.concurrent.duration.Duration
    import scala.concurrent.Await

    println(s"=========================================================")
    println(s"    PEKKO HTTP CHESS WEB SOCKET SERVER STARTED          ")
    println(s"    URL: ws://$host:$port/game                           ")
    println(s"=========================================================")
    println("Server is running. Press Ctrl+C to stop.")

    val keepAlive = Promise[Unit]()
    sys.addShutdownHook {
      keepAlive.trySuccess(())
    }

    try {
      Await.result(keepAlive.future, Duration.Inf)
    } catch {
      case _: InterruptedException => // ignore
    }

    bindingFuture
      .flatMap(_.unbind())
      .onComplete(_ => system.terminate())
  }
}
