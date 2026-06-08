package chess.application.bot

import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.Color
import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID

class InMemoryBotTurnTaskRepositorySpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  "InMemoryBotTurnTaskRepository" should "lease and complete pending tasks" in {
    val repo = new InMemoryBotTurnTaskRepository
    val now  = Instant.parse("2026-06-08T10:00:00Z")
    val task = sampleTask(now)

    repo.createPending(task).value

    val leased = repo.leaseNextPending("searchess-bot", now, leaseSeconds = 30, limit = 1).value
    leased should have size 1
    leased.head.status shouldBe BotTurnStatus.Leased
    leased.head.attemptCount shouldBe 1
    leased.head.leaseUntil.value shouldBe now.plusSeconds(30)

    repo.markCompleted(task.id, now.plusSeconds(1)).value

    val completed = repo.findById(task.id).value.value
    completed.status shouldBe BotTurnStatus.Completed
  }

  it should "mark tasks failed with a diagnostic error" in {
    val repo = new InMemoryBotTurnTaskRepository
    val now  = Instant.parse("2026-06-08T10:00:00Z")
    val task = sampleTask(now)

    repo.createPending(task).value
    repo.markFailed(task.id, "bad move", now.plusSeconds(1)).value

    val failed = repo.findById(task.id).value.value
    failed.status shouldBe BotTurnStatus.Failed
    failed.lastError.value shouldBe "bad move"
  }

  it should "release expired leases back to pending" in {
    val repo = new InMemoryBotTurnTaskRepository
    val now  = Instant.parse("2026-06-08T10:00:00Z")
    val task = sampleTask(now).copy(
      status = BotTurnStatus.Leased,
      leaseUntil = Some(now.minusSeconds(1)),
      attemptCount = 1
    )

    repo.createPending(task).value

    repo.releaseExpiredLeases(now, maxAttempts = 5).value shouldBe BotTurnLeaseSweepResult(released = 1, failed = 0)

    val released = repo.findById(task.id).value.value
    released.status shouldBe BotTurnStatus.Pending
    released.leaseUntil shouldBe None
  }

  it should "fail expired leases that reached max attempts" in {
    val repo = new InMemoryBotTurnTaskRepository
    val now  = Instant.parse("2026-06-08T10:00:00Z")
    val task = sampleTask(now).copy(
      status = BotTurnStatus.Leased,
      leaseUntil = Some(now.minusSeconds(1)),
      attemptCount = 5
    )

    repo.createPending(task).value

    repo.releaseExpiredLeases(now, maxAttempts = 5).value shouldBe BotTurnLeaseSweepResult(released = 0, failed = 1)

    val failed = repo.findById(task.id).value.value
    failed.status shouldBe BotTurnStatus.Failed
    failed.lastError.value should include("expired")
  }

  private def sampleTask(now: Instant): BotTurnTask =
    BotTurnTask(
      id = UUID.randomUUID(),
      sessionId = SessionId.random(),
      gameId = GameId.random(),
      botActorId = "searchess-bot",
      sideToMove = Color.Black,
      status = BotTurnStatus.Pending,
      leaseUntil = None,
      attemptCount = 0,
      lastError = None,
      createdAt = now,
      updatedAt = now
    )
