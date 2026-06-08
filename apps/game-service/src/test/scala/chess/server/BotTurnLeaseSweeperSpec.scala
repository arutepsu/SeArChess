package chess.server

import cats.effect.unsafe.implicits.global
import chess.application.bot.{BotTurnStatus, BotTurnTask, InMemoryBotTurnTaskRepository}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.Color
import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID

class BotTurnLeaseSweeperSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  "BotTurnLeaseSweeper.sweepOnce" should "release expired leased bot turn tasks" in {
    val now  = Instant.parse("2026-06-08T10:00:00Z")
    val repo = new InMemoryBotTurnTaskRepository
    val task = BotTurnTask(
      id = UUID.randomUUID(),
      sessionId = SessionId.random(),
      gameId = GameId.random(),
      botActorId = "searchess-bot",
      sideToMove = Color.Black,
      status = BotTurnStatus.Leased,
      leaseUntil = Some(now.minusSeconds(1)),
      attemptCount = 1,
      lastError = None,
      createdAt = now.minusSeconds(60),
      updatedAt = now.minusSeconds(30)
    )

    repo.createPending(task).value

    BotTurnLeaseSweeper.sweepOnce(repo, now, maxAttempts = 5).unsafeRunSync() shouldBe (1, 0)

    val released = repo.findById(task.id).value.value
    released.status shouldBe BotTurnStatus.Pending
    released.leaseUntil shouldBe None
    released.updatedAt shouldBe now
  }

  it should "fail expired leased bot turn tasks at max attempts" in {
    val now  = Instant.parse("2026-06-08T10:00:00Z")
    val repo = new InMemoryBotTurnTaskRepository
    val task = BotTurnTask(
      id = UUID.randomUUID(),
      sessionId = SessionId.random(),
      gameId = GameId.random(),
      botActorId = "searchess-bot",
      sideToMove = Color.Black,
      status = BotTurnStatus.Leased,
      leaseUntil = Some(now.minusSeconds(1)),
      attemptCount = 5,
      lastError = None,
      createdAt = now.minusSeconds(60),
      updatedAt = now.minusSeconds(30)
    )

    repo.createPending(task).value

    BotTurnLeaseSweeper.sweepOnce(repo, now, maxAttempts = 5).unsafeRunSync() shouldBe (0, 1)

    val failed = repo.findById(task.id).value.value
    failed.status shouldBe BotTurnStatus.Failed
    failed.lastError.value should include("expired")
    failed.updatedAt shouldBe now
  }
