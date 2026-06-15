package chess.tournamentservice

import chess.tournamentservice.db.OutboxEvent
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID

class KafkaEnvelopeBuilderSpec extends AnyFlatSpec with Matchers:

  private def makeEvent(
      id: String = UUID.randomUUID().toString,
      eventType: String = "TournamentJobCreated",
      aggregateType: String = "tournament_job",
      aggregateId: String = UUID.randomUUID().toString,
      payloadJson: String = """{"jobId":"test-id","plannedGames":2}""",
      createdAt: Instant = Instant.parse("2026-06-15T21:00:00Z")
  ): OutboxEvent =
    OutboxEvent(
      eventId       = id,
      aggregateType = aggregateType,
      aggregateId   = aggregateId,
      eventType     = eventType,
      payloadJson   = payloadJson,
      status        = "pending",
      createdAt     = createdAt,
      publishedAt   = None,
      lastError     = None,
      attemptCount  = 0
    )

  // ── envelope structure ────────────────────────────────────────────────────

  "KafkaEnvelopeBuilder" should "include all required envelope fields" in {
    val event    = makeEvent(id = "evt-1", aggregateId = "job-agg-1")
    val envelope = KafkaEnvelopeBuilder.build(event).getOrElse(fail("expected Right"))
    val parsed   = ujson.read(envelope)

    parsed("eventId").str       shouldBe "evt-1"
    parsed("eventType").str     shouldBe "TournamentJobCreated"
    parsed("aggregateType").str shouldBe "tournament_job"
    parsed("aggregateId").str   shouldBe "job-agg-1"
    parsed("occurredAt").str    shouldBe "2026-06-15T21:00:00Z"
    parsed("schemaVersion").num shouldBe 1.0
  }

  it should "embed payloadJson as a JSON object, not as an escaped string" in {
    val event    = makeEvent(payloadJson = """{"jobId":"abc","plannedGames":4}""")
    val envelope = KafkaEnvelopeBuilder.build(event).getOrElse(fail("expected Right"))
    val parsed   = ujson.read(envelope)

    // payload must be an object, not a string
    val payload = parsed("payload").obj
    payload("jobId").str           shouldBe "abc"
    payload("plannedGames").num    shouldBe 4.0
  }

  it should "set occurredAt from event.createdAt" in {
    val ts    = Instant.parse("2026-01-02T10:30:00Z")
    val event = makeEvent(createdAt = ts)
    val envelope = KafkaEnvelopeBuilder.build(event).getOrElse(fail("expected Right"))
    ujson.read(envelope)("occurredAt").str shouldBe "2026-01-02T10:30:00Z"
  }

  it should "hard-code schemaVersion to 1" in {
    val event    = makeEvent()
    val envelope = KafkaEnvelopeBuilder.build(event).getOrElse(fail("expected Right"))
    ujson.read(envelope)("schemaVersion").num shouldBe 1.0
  }

  it should "produce valid compact JSON" in {
    val event    = makeEvent()
    val envelope = KafkaEnvelopeBuilder.build(event).getOrElse(fail("expected Right"))
    noException should be thrownBy ujson.read(envelope)
  }

  it should "handle analysis_job aggregate type correctly" in {
    val event = makeEvent(
      eventType     = "AnalysisJobSucceeded",
      aggregateType = "analysis_job",
      aggregateId   = "analysis-uuid-1",
      payloadJson   = """{"analysisJobId":"analysis-uuid-1","tournamentJobId":"job-1","analyticsRunId":"run-1","outputPath":"/out","finishedAt":"2026-06-15T21:01:30Z"}"""
    )
    val envelope = KafkaEnvelopeBuilder.build(event).getOrElse(fail("expected Right"))
    val parsed   = ujson.read(envelope)

    parsed("eventType").str     shouldBe "AnalysisJobSucceeded"
    parsed("aggregateType").str shouldBe "analysis_job"
    parsed("aggregateId").str   shouldBe "analysis-uuid-1"
    parsed("payload").obj("analyticsRunId").str shouldBe "run-1"
  }

  // ── error cases ───────────────────────────────────────────────────────────

  it should "return Left for invalid payloadJson" in {
    val event  = makeEvent(payloadJson = "not valid json{{{")
    val result = KafkaEnvelopeBuilder.build(event)
    result.isLeft shouldBe true
    result.left.map(_ should include("Failed to build Kafka envelope"))
  }

  it should "return Left for empty payloadJson" in {
    val event  = makeEvent(payloadJson = "")
    val result = KafkaEnvelopeBuilder.build(event)
    result.isLeft shouldBe true
  }
