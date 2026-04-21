package chess.adapter.event

import chess.application.event.AppEvent
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, DrawReason, GameStatus, Move, Position}
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.nio.file.Files
import scala.collection.mutable

class HistoryOutboxSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val sid = SessionId.random()
  private val gid = GameId.random()

  private def tempDb(): String =
    Files.createTempFile("searchess-history-outbox-", ".sqlite").toString

  private def terminalEvent: AppEvent.GameFinished =
    AppEvent.GameFinished(sid, gid, GameStatus.Draw(DrawReason.Stalemate))

  private def nonTerminalEvent: AppEvent.MoveApplied =
    val e2 = Position.from(4, 1).toOption.get
    val e4 = Position.from(4, 3).toOption.get
    AppEvent.MoveApplied(sid, gid, Move(e2, e4), Color.White)

  "DurableHistoryEventPublisher" should "write terminal events to the outbox" in {
    val outbox = SqliteHistoryEventOutbox(tempDb())
    try
      DurableHistoryEventPublisher(outbox).publish(terminalEvent)

      val pending = outbox.pending(10).toOption.get
      pending should have size 1
      pending.head.eventType shouldBe "game.finished.v1"
      pending.head.sessionId shouldBe sid.value.toString
      pending.head.gameId shouldBe gid.value.toString
      pending.head.payloadJson shouldBe AppEventSerializer.serialize(terminalEvent).value
    finally outbox.close()
  }

  it should "ignore non-terminal events" in {
    val outbox = SqliteHistoryEventOutbox(tempDb())
    try
      DurableHistoryEventPublisher(outbox).publish(nonTerminalEvent)

      outbox.pending(10).toOption.get shouldBe empty
    finally outbox.close()
  }

  it should "absorb outbox write failures" in {
    val failing = new HistoryEventOutbox:
      def append(payloadJson: String): Either[String, Long] = Left("disk full")
      def summary(): Either[String, HistoryOutboxSummary] =
        Right(HistoryOutboxSummary(0, 0, 0, 0, None, None, Map.empty))
      def pending(limit: Int): Either[String, List[HistoryOutboxEntry]] = Right(Nil)
      def find(id: Long): Either[String, Option[HistoryOutboxEntry]] = Right(None)
      def markAttempted(id: Long): Either[String, Unit] = Right(())
      def markDelivered(id: Long): Either[String, Unit] = Right(())
      def markFailed(id: Long, error: String): Either[String, Unit] = Right(())

    noException should be thrownBy DurableHistoryEventPublisher(failing).publish(terminalEvent)
  }

  "HistoryOutboxForwarder" should "mark an entry delivered after successful POST" in {
    val outbox = SqliteHistoryEventOutbox(tempDb())
    val posts  = mutable.Buffer.empty[(URI, String, Int)]
    try
      DurableHistoryEventPublisher(outbox).publish(AppEvent.GameResigned(sid, gid, Color.Black))

      val forwarder = HistoryOutboxForwarder(
        outbox         = outbox,
        historyBaseUrl = "http://history.local:8081/",
        timeoutMillis  = 777,
        sendJson       = (uri, json, timeout) => posts += ((uri, json, timeout))
      )
      val result = forwarder.runOnce()

      posts should have size 1
      result.attempted shouldBe 1
      result.delivered shouldBe 1
      result.failed shouldBe 0
      posts.head._1.toString shouldBe s"http://history.local:8081${GameHistoryIngestionContract.GameEventsPath}"
      posts.head._3 shouldBe 777
      outbox.pending(10).toOption.get shouldBe empty
    finally outbox.close()
  }

  it should "leave failed delivery pending for retry" in {
    val outbox = SqliteHistoryEventOutbox(tempDb())
    try
      DurableHistoryEventPublisher(outbox).publish(AppEvent.SessionCancelled(sid, gid))

      val forwarder = HistoryOutboxForwarder(
        outbox         = outbox,
        historyBaseUrl = "http://history.local:8081",
        timeoutMillis  = 500,
        sendJson       = (_, _, _) => throw RuntimeException("history down")
      )
      val result = forwarder.runOnce()

      val pending = outbox.pending(10).toOption.get
      pending should have size 1
      result.attempted shouldBe 1
      result.delivered shouldBe 0
      result.failed shouldBe 1
      pending.head.attempts shouldBe 1
      pending.head.lastAttemptedAt.value should not be null
      pending.head.lastError.value should include ("history down")
      pending.head.deliveredAt shouldBe None
    finally outbox.close()
  }

  it should "record each retry attempt until delivery succeeds" in {
    val outbox = SqliteHistoryEventOutbox(tempDb())
    var fail = true
    try
      DurableHistoryEventPublisher(outbox).publish(AppEvent.SessionCancelled(sid, gid))

      val forwarder = HistoryOutboxForwarder(
        outbox         = outbox,
        historyBaseUrl = "http://history.local:8081",
        timeoutMillis  = 500,
        sendJson       = (_, _, _) =>
          if fail then
            fail = false
            throw RuntimeException("history down")
      )

      forwarder.runOnce().failed shouldBe 1
      val afterFailure = outbox.pending(10).toOption.get.head
      afterFailure.attempts shouldBe 1
      afterFailure.lastError.value should include ("history down")

      val recovery = forwarder.runOnce()

      recovery.delivered shouldBe 1
      outbox.pending(10).toOption.get shouldBe empty
      val delivered = outbox.find(afterFailure.id).toOption.get.value
      delivered.attempts shouldBe 2
      delivered.lastError shouldBe None
      delivered.deliveredAt.value should not be null
    finally outbox.close()
  }

  it should "reload pending entries from the same SQLite file after restart" in {
    val db = tempDb()
    val outbox1 = SqliteHistoryEventOutbox(db)
    DurableHistoryEventPublisher(outbox1).publish(terminalEvent)
    val id = outbox1.pending(10).toOption.get.head.id
    outbox1.close()

    val outbox2 = SqliteHistoryEventOutbox(db)
    try
      val pending = outbox2.pending(10).toOption.get
      pending.map(_.id) should contain (id)
      pending.head.payloadJson shouldBe AppEventSerializer.serialize(terminalEvent).value
    finally outbox2.close()
  }
