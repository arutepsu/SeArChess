package chess.tournamentservice

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import chess.tournamentservice.db.{InMemoryOutboxEventRepository, OutboxEvent}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

class OutboxPollerSpec extends AnyFlatSpec with Matchers:

  private def makeEvent(
      id: String = UUID.randomUUID().toString,
      createdAt: Instant = Instant.now()
  ): OutboxEvent =
    OutboxEvent(
      eventId       = id,
      aggregateType = "tournament_job",
      aggregateId   = UUID.randomUUID().toString,
      eventType     = "TournamentJobCreated",
      payloadJson   = s"""{"jobId":"$id"}""",
      status        = "pending",
      createdAt     = createdAt,
      publishedAt   = None,
      lastError     = None,
      attemptCount  = 0
    )

  private def freshPoller(
      repo: InMemoryOutboxEventRepository,
      publisher: OutboxPublisher,
      batchSize: Int = 10
  ): OutboxPoller =
    OutboxPoller(repo, publisher, pollInterval = 60.seconds, batchSize = batchSize)

  private def get[A](opt: Option[A], label: String): A =
    opt.getOrElse(fail(s"expected $label to be defined"))

  // ── 1. publish pending event ───────────────────────────────────────────────

  "OutboxPoller" should "publish a pending event and mark it published" in {
    val event  = makeEvent("evt-publish-1")
    val repo   = InMemoryOutboxEventRepository.createWith(List(event)).unsafeRunSync()
    val poller = freshPoller(repo, LoggingOutboxPublisher)

    poller.pollOnce().unsafeRunSync()

    val updated = get(repo.allEvents().unsafeRunSync().find(_.eventId == event.eventId), "event")
    updated.status      shouldBe "published"
    updated.publishedAt shouldBe defined
  }

  it should "not re-process already published events" in {
    val published = makeEvent("evt-already-pub").copy(status = "published")
    val pending   = makeEvent("evt-still-pending")
    val repo      = InMemoryOutboxEventRepository.createWith(List(published, pending)).unsafeRunSync()
    val counter   = Ref.of[IO, Int](0).unsafeRunSync()
    val counting  = new OutboxPublisher:
      def publish(e: OutboxEvent): IO[Unit] = counter.update(_ + 1)
    val poller    = freshPoller(repo, counting)

    poller.pollOnce().unsafeRunSync()

    counter.get.unsafeRunSync() shouldBe 1
  }

  // ── 2. mark failed on publisher error ─────────────────────────────────────

  it should "mark event failed when publisher raises an error" in {
    val event   = makeEvent("evt-fail-1")
    val repo    = InMemoryOutboxEventRepository.createWith(List(event)).unsafeRunSync()
    val failing = new OutboxPublisher:
      def publish(e: OutboxEvent): IO[Unit] = IO.raiseError(RuntimeException("kafka down"))
    val poller  = freshPoller(repo, failing)

    poller.pollOnce().unsafeRunSync()

    val updated = get(repo.allEvents().unsafeRunSync().find(_.eventId == event.eventId), "event")
    updated.status                    shouldBe "failed"
    updated.attemptCount              shouldBe 1
    updated.lastError.map(_.contains("kafka down")) shouldBe Some(true)
    updated.publishedAt               shouldBe None
  }

  // ── 3. continue after one failure ─────────────────────────────────────────

  it should "continue processing remaining events after one event fails" in {
    val t0   = Instant.now().minusSeconds(10)
    val t1   = Instant.now()
    val evt1 = makeEvent("evt-first", createdAt = t0)
    val evt2 = makeEvent("evt-second", createdAt = t1)
    val repo  = InMemoryOutboxEventRepository.createWith(List(evt1, evt2)).unsafeRunSync()

    val counter = Ref.of[IO, Int](0).unsafeRunSync()
    val flaky   = new OutboxPublisher:
      def publish(e: OutboxEvent): IO[Unit] =
        counter.update(_ + 1) >> {
          if e.eventId == evt1.eventId then IO.raiseError(RuntimeException("first fails"))
          else IO.unit
        }
    val poller = freshPoller(repo, flaky)

    poller.pollOnce().unsafeRunSync()

    counter.get.unsafeRunSync() shouldBe 2

    val all = repo.allEvents().unsafeRunSync()
    get(all.find(_.eventId == evt1.eventId), "evt1").status shouldBe "failed"
    get(all.find(_.eventId == evt2.eventId), "evt2").status shouldBe "published"
  }

  // ── 4. batch size limit ────────────────────────────────────────────────────

  it should "only publish up to batchSize events per poll" in {
    val events  = (1 to 5).toList.map(i => makeEvent(s"batch-$i", createdAt = Instant.now().minusSeconds(10 - i)))
    val repo    = InMemoryOutboxEventRepository.createWith(events).unsafeRunSync()
    val counter = Ref.of[IO, Int](0).unsafeRunSync()
    val counting = new OutboxPublisher:
      def publish(e: OutboxEvent): IO[Unit] = counter.update(_ + 1)
    val poller  = freshPoller(repo, counting, batchSize = 3)

    poller.pollOnce().unsafeRunSync()

    counter.get.unsafeRunSync() shouldBe 3
    repo.countPending().unsafeRunSync() shouldBe 2
  }

  // ── 5. ordering ───────────────────────────────────────────────────────────

  it should "publish events in createdAt ascending order" in {
    val t0   = Instant.now().minusSeconds(20)
    val t1   = Instant.now().minusSeconds(10)
    val t2   = Instant.now()
    val evt0 = makeEvent("order-0", createdAt = t0)
    val evt1 = makeEvent("order-1", createdAt = t1)
    val evt2 = makeEvent("order-2", createdAt = t2)
    // insert out of order
    val repo  = InMemoryOutboxEventRepository.createWith(List(evt2, evt0, evt1)).unsafeRunSync()

    val published = Ref.of[IO, List[String]](Nil).unsafeRunSync()
    val recording = new OutboxPublisher:
      def publish(e: OutboxEvent): IO[Unit] = published.update(_ :+ e.eventId)
    val poller = freshPoller(repo, recording)

    poller.pollOnce().unsafeRunSync()

    published.get.unsafeRunSync() shouldBe List("order-0", "order-1", "order-2")
  }
