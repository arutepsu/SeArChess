package chess.streaming

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.stream.ClosedShape
import org.apache.pekko.stream.scaladsl.{Broadcast, BroadcastHub, Flow, GraphDSL, Keep, MergeHub, RunnableGraph, Sink, Source}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.ExecutionContext

class GameRoom(val gameId: String)(implicit system: ActorSystem) {
  implicit val ec: ExecutionContext = system.dispatcher

  @volatile var currentState: GameState = GameState.initial

  // Materialisiert den Raum-Stream mithilfe der Pekko GraphDSL (nicht-lineare Topologie)
  val (hubSink, hubSource) = RunnableGraph.fromGraph(
    GraphDSL.createGraph(MergeHub.source[Either[Throwable, Move]], BroadcastHub.sink[Either[String, GameState]])(Keep.both) { implicit builder => (mergeSource, broadcastSink) =>
      import GraphDSL.Implicits._

      val validator = builder.add(ChessStreamingEngine.validatorFlow)

      // Aktualisiert den internen Zustand des Raums
      val stateUpdater = builder.add(Flow[Either[String, GameState]].map { result =>
        result.foreach { state =>
          currentState = state
        }
        result
      })

      // Broadcast Junction, um den Datenstrom aufzuteilen (Duplizierung)
      val broadcast = builder.add(Broadcast[Either[String, GameState]](2))

      // Zweite Senke (Sink): Ein raumspezifischer Logger fuer die Konsole
      val loggingSink = builder.add(Sink.foreach[Either[String, GameState]] {
        case Right(state) =>
          println(s"[Room-Logger-$gameId] Valider Zug! Zuege gespielt: ${state.moveHistory.length}. Letzter Zug: ${state.moveHistory.lastOption.map(m => s"${m.from}-${m.to}").getOrElse("-")}")
        case Left(error) =>
          System.err.println(s"[Room-Logger-$gameId] Warnung: $error")
      })

      // Verdrahtung des Graphen: Ingress -> Validator -> StateUpdater -> Broadcast -> Sinks
      mergeSource ~> validator ~> stateUpdater ~> broadcast.in
      broadcast.out(0) ~> broadcastSink
      broadcast.out(1) ~> loggingSink

      ClosedShape
    }
  ).run()
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

    val indexHtmlPath = sys.env.getOrElse("INDEX_HTML_PATH", "apps/chess-streaming/index.html")

    val route =
      pathSingleSlash {
        get {
          getFromFile(indexHtmlPath)
        }
      } ~
      path("game") {
        parameter("gameId".?) {
          case Some(gameId) => handleWebSocketMessages(webSocketFlow(gameId))
          case None         => handleWebSocketMessages(webSocketFlow("default"))
        }
      } ~
      path("game" / Segment) { gameId =>
        handleWebSocketMessages(webSocketFlow(gameId))
      }

    val host = sys.env.getOrElse("STREAMING_HOST", "0.0.0.0")
    val port = sys.env.getOrElse("STREAMING_PORT", "8082").toInt

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
