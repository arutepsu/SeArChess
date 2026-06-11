package chess.streaming

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.*

final class SearchessRoomHttpAdapterSpec extends AnyFlatSpec with Matchers:

  "SearchessRoomHttpAdapter" should "route WebSocket JSON commands into a Searchess room" in {
    implicit val system: ActorSystem = ActorSystem("SearchessRoomHttpAdapterJsonSpec")
    val registry = SearchessRoomRegistry()
    val adapter = SearchessRoomHttpAdapter(registry)

    try {
      val messages = Source(List(
        TextMessage.Strict("""{"line":"session ws-json"}"""),
        TextMessage.Strict("""{"line":"players Alice Bob"}"""),
        TextMessage.Strict("""{"line":"move Alice e2e4"}""")
      ))

      val output = Await.result(
        messages
          .via(adapter.webSocketFlow("room-json"))
          .collect { case TextMessage.Strict(text) => text }
          .take(4)
          .runWith(Sink.seq),
        5.seconds
      )

      val eventTypes = output.drop(1).map(text => ujson.read(text)("event")("eventType").str)
      eventTypes shouldBe Seq("SessionStarted", "PlayersRegistered", "MoveAccepted")
      output.head should include("connected")
    } finally {
      registry.closeAll()
      Await.result(system.terminate(), 5.seconds)
    }
  }

  it should "route raw DSL WebSocket commands into a Searchess room" in {
    implicit val system: ActorSystem = ActorSystem("SearchessRoomHttpAdapterRawSpec")
    val registry = SearchessRoomRegistry()
    val adapter = SearchessRoomHttpAdapter(registry)

    try {
      val messages = Source(List(
        TextMessage.Strict("session ws-raw"),
        TextMessage.Strict("players Carol Dave"),
        TextMessage.Strict("move Carol d2d4")
      ))

      val output = Await.result(
        messages
          .via(adapter.webSocketFlow("room-raw"))
          .collect { case TextMessage.Strict(text) => text }
          .take(4)
          .runWith(Sink.seq),
        5.seconds
      )

      val lastEvent = ujson.read(output.last)("event")
      lastEvent("eventType").str shouldBe "MoveAccepted"
      lastEvent("payload").str should include("Carol d2d4")
    } finally {
      registry.closeAll()
      Await.result(system.terminate(), 5.seconds)
    }
  }

  it should "expose room dead letters over a dedicated WebSocket flow" in {
    implicit val system: ActorSystem = ActorSystem("SearchessRoomHttpAdapterDeadLetterSpec")
    val registry = SearchessRoomRegistry()
    val adapter = SearchessRoomHttpAdapter(registry)

    try {
      val messages = Source(List(
        TextMessage.Strict("unknown abc"),
        TextMessage.Strict("move Alice e2e4")
      ))

      val output = Await.result(
        messages
          .via(adapter.webSocketFlow("room-dead-websocket", deadLettersOnly = true))
          .collect { case TextMessage.Strict(text) => text }
          .take(3)
          .runWith(Sink.seq),
        5.seconds
      )

      val eventTypes = output.drop(1).map(text => ujson.read(text)("event")("eventType").str)
      eventTypes shouldBe Seq("ParseFailed", "ValidationFailed")
    } finally {
      registry.closeAll()
      Await.result(system.terminate(), 5.seconds)
    }
  }
