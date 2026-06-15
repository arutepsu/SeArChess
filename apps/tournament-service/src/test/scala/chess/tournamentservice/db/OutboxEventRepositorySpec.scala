package chess.tournamentservice.db

import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID

class OutboxEventRepositorySpec extends AnyFlatSpec with Matchers:

  private def makeEvent(
      id: String = UUID.randomUUID().toString,
      createdAt: Instant = Instant.now(),
      status: String = "pending"
  ): OutboxEvent =
    OutboxEvent(
      eventId       = id,
      aggregateType = "tournament_job",
      aggregateId   = UUID.randomUUID().toString,
      eventType     = "TournamentJobCreated",
      payloadJson   = s"""{"jobId":"$id"}""",
      status        = status,
      createdAt     = createdAt,
      publishedAt   = None,
      lastError     = None,
      attemptCount  = 0
    )

  private def get[A](opt: Option[A], label: String): A =
    opt.getOrElse(fail(s"expected $label to be defined"))

  // ── fetchPending ──────────────────────────────────────────────────────────

  "InMemoryOutboxEventRepository" should "return only pending events ordered by createdAt ASC" in {
    val t0   = Instant.now().minusSeconds(10)
    val t1   = Instant.now().minusSeconds(5)
    val t2   = Instant.now()
    val evt0 = makeEvent("id-0", createdAt = t0)
    val evt1 = makeEvent("id-1", createdAt = t1)
    val evt2 = makeEvent("id-2", createdAt = t2, status = "published")
    val repo = InMemoryOutboxEventRepository.createWith(List(evt1, evt0, evt2)).unsafeRunSync()

    val result = repo.fetchPending(10).unsafeRunSync()

    result.map(_.eventId) shouldBe List("id-0", "id-1")
  }

  it should "respect the limit in fetchPending" in {
    val events = (1 to 5).toList.map(i => makeEvent(s"id-$i", createdAt = Instant.now().minusSeconds(10 - i)))
    val repo   = InMemoryOutboxEventRepository.createWith(events).unsafeRunSync()

    val result = repo.fetchPending(3).unsafeRunSync()
    result.size shouldBe 3
  }

  it should "return empty list when no pending events exist" in {
    val repo = InMemoryOutboxEventRepository.create().unsafeRunSync()
    repo.fetchPending(10).unsafeRunSync() shouldBe List.empty
  }

  // ── markPublished ─────────────────────────────────────────────────────────

  it should "set status=published and publishedAt when markPublished is called" in {
    val event = makeEvent("pub-1")
    val repo  = InMemoryOutboxEventRepository.createWith(List(event)).unsafeRunSync()

    repo.markPublished(event.eventId).unsafeRunSync()

    val all      = repo.allEvents().unsafeRunSync()
    val updated  = get(all.find(_.eventId == event.eventId), "event")
    updated.status       shouldBe "published"
    updated.publishedAt  shouldBe defined
    updated.attemptCount shouldBe 0
    updated.lastError    shouldBe None
  }

  it should "leave other events unaffected by markPublished" in {
    val evt1 = makeEvent("pub-a")
    val evt2 = makeEvent("pub-b")
    val repo  = InMemoryOutboxEventRepository.createWith(List(evt1, evt2)).unsafeRunSync()

    repo.markPublished(evt1.eventId).unsafeRunSync()

    val all  = repo.allEvents().unsafeRunSync()
    get(all.find(_.eventId == evt2.eventId), "evt2").status shouldBe "pending"
  }

  // ── markFailed ────────────────────────────────────────────────────────────

  it should "set status=failed, increment attemptCount, and store lastError when markFailed is called" in {
    val event = makeEvent("fail-1")
    val repo  = InMemoryOutboxEventRepository.createWith(List(event)).unsafeRunSync()

    repo.markFailed(event.eventId, "simulated publish error").unsafeRunSync()

    val all     = repo.allEvents().unsafeRunSync()
    val updated = get(all.find(_.eventId == event.eventId), "event")
    updated.status       shouldBe "failed"
    updated.attemptCount shouldBe 1
    updated.lastError    shouldBe Some("simulated publish error")
    updated.publishedAt  shouldBe None
  }

  it should "accumulate attemptCount across repeated markFailed calls" in {
    val event = makeEvent("fail-2")
    val repo  = InMemoryOutboxEventRepository.createWith(List(event)).unsafeRunSync()

    repo.markFailed(event.eventId, "error 1").unsafeRunSync()
    repo.markFailed(event.eventId, "error 2").unsafeRunSync()

    val all     = repo.allEvents().unsafeRunSync()
    val updated = get(all.find(_.eventId == event.eventId), "event")
    updated.attemptCount shouldBe 2
    updated.lastError    shouldBe Some("error 2")
  }

  // ── countPending / countFailed ────────────────────────────────────────────

  it should "count pending and failed events correctly" in {
    val p1   = makeEvent("c-1", status = "pending")
    val p2   = makeEvent("c-2", status = "pending")
    val f1   = makeEvent("c-3", status = "failed")
    val pub1 = makeEvent("c-4", status = "published")
    val repo  = InMemoryOutboxEventRepository.createWith(List(p1, p2, f1, pub1)).unsafeRunSync()

    repo.countPending().unsafeRunSync() shouldBe 2
    repo.countFailed().unsafeRunSync()  shouldBe 1
  }
