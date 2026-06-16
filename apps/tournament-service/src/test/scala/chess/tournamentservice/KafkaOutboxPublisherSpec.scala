package chess.tournamentservice

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import chess.tournamentservice.db.OutboxEvent
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID

class KafkaOutboxPublisherSpec extends AnyFlatSpec with Matchers:

  private val testTopic = "searchess.tournament.lifecycle.v1"

  // FakeKafkaRecordSender captures (topic, key, value) tuples for assertions
  private final class FakeKafkaRecordSender(
      records: Ref[IO, List[(String, String, String)]]
  ) extends KafkaRecordSender:
    def send(topic: String, key: String, value: String): IO[Unit] =
      records.update(_ :+ (topic, key, value))
    def close(): IO[Unit] = IO.unit
    def recorded(): IO[List[(String, String, String)]] = records.get

  private object FakeKafkaRecordSender:
    def create(): FakeKafkaRecordSender =
      val records = Ref.of[IO, List[(String, String, String)]](Nil).unsafeRunSync()
      new FakeKafkaRecordSender(records)

  private def makeEvent(
      id: String = UUID.randomUUID().toString,
      aggregateId: String = UUID.randomUUID().toString,
      payloadJson: String = """{"jobId":"test-job","plannedGames":2}"""
  ): OutboxEvent =
    OutboxEvent(
      eventId       = id,
      aggregateType = "tournament_job",
      aggregateId   = aggregateId,
      eventType     = "TournamentJobCreated",
      payloadJson   = payloadJson,
      status        = "pending",
      createdAt     = Instant.now(),
      publishedAt   = None,
      lastError     = None,
      attemptCount  = 0
    )

  private def get[A](opt: Option[A], label: String): A =
    opt.getOrElse(fail(s"expected $label to be defined"))

  // ── routing ───────────────────────────────────────────────────────────────

  "KafkaOutboxPublisher" should "send to the configured topic" in {
    val sender    = FakeKafkaRecordSender.create()
    val publisher = KafkaOutboxPublisher(sender, testTopic)
    val event     = makeEvent()

    publisher.publish(event).unsafeRunSync()

    val recs = sender.recorded().unsafeRunSync()
    recs.size shouldBe 1
    recs.head._1 shouldBe testTopic
  }

  it should "use aggregateId as the Kafka message key" in {
    val sender    = FakeKafkaRecordSender.create()
    val publisher = KafkaOutboxPublisher(sender, testTopic)
    val event     = makeEvent(aggregateId = "specific-aggregate-id")

    publisher.publish(event).unsafeRunSync()

    val recs = sender.recorded().unsafeRunSync()
    recs.head._2 shouldBe "specific-aggregate-id"
  }

  // ── envelope ──────────────────────────────────────────────────────────────

  it should "send a valid envelope with correct fields as the message value" in {
    val sender    = FakeKafkaRecordSender.create()
    val publisher = KafkaOutboxPublisher(sender, testTopic)
    val event     = makeEvent(id = "env-evt-1", aggregateId = "agg-1")

    publisher.publish(event).unsafeRunSync()

    val value  = sender.recorded().unsafeRunSync().head._3
    val parsed = ujson.read(value)

    parsed("eventId").str       shouldBe "env-evt-1"
    parsed("aggregateId").str   shouldBe "agg-1"
    parsed("eventType").str     shouldBe "TournamentJobCreated"
    parsed("schemaVersion").num shouldBe 1.0
    parsed.obj.contains("payload") shouldBe true
  }

  it should "embed payloadJson as an object in the envelope, not an escaped string" in {
    val sender    = FakeKafkaRecordSender.create()
    val publisher = KafkaOutboxPublisher(sender, testTopic)
    val event     = makeEvent(payloadJson = """{"jobId":"j1","plannedGames":4}""")

    publisher.publish(event).unsafeRunSync()

    val value   = sender.recorded().unsafeRunSync().head._3
    val payload = ujson.read(value)("payload")

    // must be an object, not a string
    payload.obj("jobId").str        shouldBe "j1"
    payload.obj("plannedGames").num shouldBe 4.0
  }

  // ── error cases ───────────────────────────────────────────────────────────

  it should "fail publish (raise IO error) when payloadJson is invalid" in {
    val sender    = FakeKafkaRecordSender.create()
    val publisher = KafkaOutboxPublisher(sender, testTopic)
    val event     = makeEvent(payloadJson = "{broken json")

    val result = publisher.publish(event).attempt.unsafeRunSync()

    result.isLeft shouldBe true
    sender.recorded().unsafeRunSync() shouldBe Nil
  }

  it should "propagate sender errors so the poller can mark the event failed" in {
    val failingSender = new KafkaRecordSender:
      def send(topic: String, key: String, value: String): IO[Unit] =
        IO.raiseError(RuntimeException("broker unavailable"))
      def close(): IO[Unit] = IO.unit
    val publisher = KafkaOutboxPublisher(failingSender, testTopic)
    val event     = makeEvent()

    val result = publisher.publish(event).attempt.unsafeRunSync()

    result.isLeft shouldBe true
    result.left.map(_.getMessage should include("broker unavailable"))
  }

  it should "send one message per publish call (no batching)" in {
    val sender    = FakeKafkaRecordSender.create()
    val publisher = KafkaOutboxPublisher(sender, testTopic)

    publisher.publish(makeEvent("e1")).unsafeRunSync()
    publisher.publish(makeEvent("e2")).unsafeRunSync()
    publisher.publish(makeEvent("e3")).unsafeRunSync()

    sender.recorded().unsafeRunSync().size shouldBe 3
  }
