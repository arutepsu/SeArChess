package chess.streaming

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.QueueOfferResult
import org.apache.pekko.stream.scaladsl.Sink
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.*

final class SearchessRoomStreamsSpec extends AnyFlatSpec with Matchers:

  "SearchessRoomStream" should "process live DSL commands through the Searchess pipeline" in {
    implicit val system: ActorSystem = ActorSystem("SearchessRoomStreamLiveSpec")
    val room = SearchessRoomStream.create("room-live")

    try {
      val eventsFuture = room.events.take(5).runWith(Sink.seq)

      submitAll(room, List(
        "session room-live-session",
        "players Alice Bob",
        "move Alice e2e4",
        "move Bob e7e5",
        "status"
      ))

      val events = Await.result(eventsFuture, 5.seconds)

      events.map(_.eventType) shouldBe Seq(
        "SessionStarted",
        "PlayersRegistered",
        "MoveAccepted",
        "MoveAccepted",
        "StatusSnapshot"
      )
      events.forall(_.sessionId.contains("room-live-session")) shouldBe true
    } finally {
      room.close()
      Await.result(system.terminate(), 5.seconds)
    }
  }

  it should "keep game state isolated per room" in {
    implicit val system: ActorSystem = ActorSystem("SearchessRoomStreamIsolationSpec")
    val firstRoom = SearchessRoomStream.create("room-a")
    val secondRoom = SearchessRoomStream.create("room-b")

    try {
      val firstEventsFuture = firstRoom.events.take(3).runWith(Sink.seq)
      val secondEventsFuture = secondRoom.events.take(3).runWith(Sink.seq)

      submitAll(firstRoom, List(
        "session session-a",
        "players Alice Bob",
        "move Alice e2e4"
      ))
      submitAll(secondRoom, List(
        "session session-b",
        "players Carol Dave",
        "move Carol d2d4"
      ))

      val firstEvents = Await.result(firstEventsFuture, 5.seconds)
      val secondEvents = Await.result(secondEventsFuture, 5.seconds)

      firstEvents.map(_.sessionId) should contain only Some("session-a")
      secondEvents.map(_.sessionId) should contain only Some("session-b")
      firstEvents.last.payload should include("Alice e2e4")
      secondEvents.last.payload should include("Carol d2d4")
    } finally {
      firstRoom.close()
      secondRoom.close()
      Await.result(system.terminate(), 5.seconds)
    }
  }

  it should "provide batched room events" in {
    implicit val system: ActorSystem = ActorSystem("SearchessRoomStreamBatchSpec")
    val room = SearchessRoomStream.create("room-batches")

    try {
      val batchesFuture = room.eventBatches.take(2).runWith(Sink.seq)

      submitAll(room, List(
        "session batched-session",
        "players Alice Bob",
        "move Alice e2e4",
        "move Bob e7e5",
        "status",
        "resign Bob"
      ))

      val batches = Await.result(batchesFuture, 5.seconds)

      batches.map(_.size) shouldBe Seq(5, 2)
      batches.flatten.map(_.eventType) should contain("GameFinished")
    } finally {
      room.close()
      Await.result(system.terminate(), 5.seconds)
    }
  }

  "SearchessRoomRegistry" should "create, reuse, list, and close rooms" in {
    implicit val system: ActorSystem = ActorSystem("SearchessRoomRegistrySpec")
    val registry = SearchessRoomRegistry()

    try {
      val roomA = registry.getOrCreate("room-a")
      val roomAAgain = registry.getOrCreate("room-a")
      val roomB = registry.getOrCreate("room-b")

      roomAAgain shouldBe theSameInstanceAs(roomA)
      roomB should not be theSameInstanceAs(roomA)
      registry.activeRoomIds shouldBe Set("room-a", "room-b")

      registry.close("room-a") shouldBe true
      registry.get("room-a") shouldBe None
      registry.activeRoomIds shouldBe Set("room-b")
    } finally {
      registry.closeAll()
      Await.result(system.terminate(), 5.seconds)
    }
  }

  private def submitAll(room: SearchessRoomStream, lines: List[String])(implicit
      system: ActorSystem
  ): Unit =
    val results = Await.result(room.submitAll(lines), 5.seconds)
    results.foreach(_ shouldBe QueueOfferResult.Enqueued)
