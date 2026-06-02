package chess.streaming

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.stream.scaladsl.Flow
import scala.concurrent.ExecutionContext
import scala.io.StdIn

object ChessStreamingServerMain {

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("ChessStreamingServerSystem")
    implicit val ec: ExecutionContext = system.dispatcher

    // WebSocket handler logic
    def webSocketFlow: Flow[Message, Message, Any] = {
      // Ingoing flow: Extracts raw move from incoming JSON or text message
      val incomingFlow: Flow[Message, String, Any] = Flow[Message].collect {
        case TextMessage.Strict(text) =>
          try {
            val json = ujson.read(text)
            json("move").str
          } catch {
            case _: Exception => text // Fallback to raw text message if not JSON
          }
      }

      // Outgoing flow: Converts GameState/Error into JSON TextMessage
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

      // Combine parsing & validation flows from ChessStreamingEngine
      val chessFlow = ChessStreamingEngine.parserFlow
        .via(ChessStreamingEngine.validatorFlow)

      // Connect everything: Ingest -> Parse -> Validate -> Serialize
      incomingFlow.via(chessFlow).via(outgoingFlow)
    }

    val route =
      path("game") {
        handleWebSocketMessages(webSocketFlow)
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
