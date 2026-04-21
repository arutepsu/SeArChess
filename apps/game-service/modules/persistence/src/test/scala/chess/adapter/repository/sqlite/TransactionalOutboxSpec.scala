package chess.adapter.repository.sqlite

import chess.adapter.event.{AppEventSerializer, SqliteHistoryEventOutbox}
import chess.application.ChessService
import chess.application.event.AppEvent
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, GameStatus}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.time.Instant

/** Verifies that [[SqliteSessionGameStore.saveTerminal]] and
  * [[SqliteSessionRepository.saveCancelWithOutbox]] commit game/session state and the outbox row in
  * a single JDBC transaction.
  *
  * Each test uses an isolated temp file. "Restart" tests open a second [[SqliteHistoryEventOutbox]]
  * against the same file to verify durability.
  */
class TransactionalOutboxSpec extends AnyFlatSpec with Matchers with EitherValues:

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def tempPath(): String =
    Files.createTempFile("searchess-tx-test-", ".sqlite").toAbsolutePath.toString

  private def freshDs(path: String): SqliteDataSource =
    val ds = SqliteDataSource(path)
    ds.withConnection(SqliteSchema.createTables)
    ds

  private def freshStoreSetup(path: String) =
    val ds = freshDs(path)
    val sessRepo = SqliteSessionRepository(ds)
    val gameRepo = SqliteGameRepository(ds)
    val store = SqliteSessionGameStore(ds, sessRepo, gameRepo)
    val outbox = SqliteHistoryEventOutbox(path)
    (ds, store, sessRepo, gameRepo, outbox)

  private def freshSession(gameId: GameId = GameId.random()): GameSession =
    GameSession.create(
      gameId,
      SessionMode.HumanVsHuman,
      SideController.HumanLocal,
      SideController.HumanLocal,
      now = Instant.parse("2024-01-01T00:00:00Z")
    )

  private def checkmateState =
    ChessService.createNewGame().copy(status = GameStatus.Checkmate(Color.White))

  private def resignedState =
    ChessService.createNewGame().copy(status = GameStatus.Resigned(Color.Black))

  private def gameFinishedPayload(sid: SessionId, gid: GameId): String =
    AppEventSerializer
      .serialize(AppEvent.GameFinished(sid, gid, GameStatus.Checkmate(Color.White)))
      .getOrElse(fail("GameFinished serialization returned None"))

  private def gameResignedPayload(sid: SessionId, gid: GameId): String =
    AppEventSerializer
      .serialize(AppEvent.GameResigned(sid, gid, Color.Black))
      .getOrElse(fail("GameResigned serialization returned None"))

  private def sessionCancelledPayload(sid: SessionId, gid: GameId): String =
    AppEventSerializer
      .serialize(AppEvent.SessionCancelled(sid, gid))
      .getOrElse(fail("SessionCancelled serialization returned None"))

  // ── SqliteSessionGameStore.saveTerminal — success ─────────────────────────

  "SqliteSessionGameStore.saveTerminal" should
    "persist game state and outbox row in the same transaction on success" in {
      val path = tempPath()
      val (_, store, _, gameRepo, outbox) = freshStoreSetup(path)
      val session = freshSession()
      val payload = gameFinishedPayload(session.sessionId, session.gameId)

      store.saveTerminal(session, checkmateState, List(payload)).value

      gameRepo.load(session.gameId).isRight shouldBe true
      val pending = outbox.pending(10).value
      pending should have size 1
      pending.head.payloadJson shouldBe payload
      outbox.close()
    }

  it should "behave identically to save when outboxPayloads is empty" in {
    val path = tempPath()
    val (_, store, _, gameRepo, outbox) = freshStoreSetup(path)
    val session = freshSession()

    store.saveTerminal(session, checkmateState, Nil).value

    gameRepo.load(session.gameId).isRight shouldBe true
    outbox.pending(10).value shouldBe empty
    outbox.close()
  }

  // ── SqliteSessionGameStore.saveTerminal — rollback on outbox failure ──────

  it should "roll back game state when outbox insert fails due to a malformed payload" in {
    val path = tempPath()
    val (_, store, _, gameRepo, outbox) = freshStoreSetup(path)
    val session = freshSession()
    val badPayload = "{}" // valid JSON but missing type/sessionId/gameId

    val result = store.saveTerminal(session, checkmateState, List(badPayload))

    result shouldBe a[Left[_, _]]
    gameRepo.load(session.gameId) shouldBe a[Left[_, _]]
    outbox.pending(10).value shouldBe empty
    outbox.close()
  }

  it should "roll back game state and no outbox row when outbox table is absent" in {
    val path = tempPath()
    val (ds, store, _, gameRepo, outbox) = freshStoreSetup(path)
    val session = freshSession()
    val payload = gameFinishedPayload(session.sessionId, session.gameId)

    ds.withConnection { conn => conn.createStatement().execute("DROP TABLE history_event_outbox") }

    val result = store.saveTerminal(session, checkmateState, List(payload))

    result shouldBe a[Left[_, _]]
    gameRepo.load(session.gameId) shouldBe a[Left[_, _]]
    outbox.close()
  }

  // ── SqliteSessionGameStore.saveTerminal — rollback on state write failure ─

  it should "not write an outbox row when the game_states write fails" in {
    val path = tempPath()
    val (ds, store, _, gameRepo, outbox) = freshStoreSetup(path)
    val session = freshSession()
    val payload = gameResignedPayload(session.sessionId, session.gameId)

    ds.withConnection { conn => conn.createStatement().execute("DROP TABLE game_states") }

    val result = store.saveTerminal(session, resignedState, List(payload))

    result shouldBe a[Left[_, _]]
    outbox.pending(10).value shouldBe empty
    outbox.close()
  }

  // ── SqliteSessionGameStore.saveTerminal — restart durability ─────────────

  it should "retain the pending outbox entry after a simulated restart" in {
    val path = tempPath()
    val session = freshSession()
    val payload = gameFinishedPayload(session.sessionId, session.gameId)

    val (_, store1, _, _, outbox1) = freshStoreSetup(path)
    store1.saveTerminal(session, checkmateState, List(payload)).value
    outbox1.pending(10).value should have size 1
    outbox1.close()

    val outbox2 = SqliteHistoryEventOutbox(path)
    val pending = outbox2.pending(10).value
    pending should have size 1
    pending.head.payloadJson shouldBe payload
    outbox2.close()
  }

  // ── SqliteSessionRepository.saveCancelWithOutbox — success ───────────────

  "SqliteSessionRepository.saveCancelWithOutbox" should
    "persist session update and outbox row atomically on success" in {
      val path = tempPath()
      val ds = freshDs(path)
      val repo = SqliteSessionRepository(ds)
      val outbox = SqliteHistoryEventOutbox(path)

      val session = freshSession()
      repo.save(session).value
      val cancelled = GameSession.withLifecycle(session, SessionLifecycle.Cancelled, Instant.now())
      val payload = sessionCancelledPayload(session.sessionId, session.gameId)

      repo.saveCancelWithOutbox(cancelled, Some(payload)).value

      repo.load(session.sessionId).value.lifecycle shouldBe SessionLifecycle.Cancelled
      val pending = outbox.pending(10).value
      pending should have size 1
      pending.head.payloadJson shouldBe payload
      outbox.close()
    }

  it should "behave identically to save when outboxPayload is None" in {
    val path = tempPath()
    val ds = freshDs(path)
    val repo = SqliteSessionRepository(ds)
    val outbox = SqliteHistoryEventOutbox(path)

    val session = freshSession()
    repo.save(session).value
    val cancelled = GameSession.withLifecycle(session, SessionLifecycle.Cancelled, Instant.now())

    repo.saveCancelWithOutbox(cancelled, None).value

    repo.load(session.sessionId).value.lifecycle shouldBe SessionLifecycle.Cancelled
    outbox.pending(10).value shouldBe empty
    outbox.close()
  }

  // ── SqliteSessionRepository.saveCancelWithOutbox — rollback on outbox fail ─

  it should "roll back the session update when the outbox insert fails (malformed payload)" in {
    val path = tempPath()
    val ds = freshDs(path)
    val repo = SqliteSessionRepository(ds)

    val session = freshSession()
    repo.save(session).value
    val cancelled = GameSession.withLifecycle(session, SessionLifecycle.Cancelled, Instant.now())
    val badPayload = "{}"

    val result = repo.saveCancelWithOutbox(cancelled, Some(badPayload))

    result shouldBe a[Left[_, _]]
    repo.load(session.sessionId).value.lifecycle shouldBe SessionLifecycle.Created
  }

  it should "roll back the session update when the outbox table is absent" in {
    val path = tempPath()
    val ds = freshDs(path)
    val repo = SqliteSessionRepository(ds)
    val outbox = SqliteHistoryEventOutbox(path)

    val session = freshSession()
    repo.save(session).value
    val cancelled = GameSession.withLifecycle(session, SessionLifecycle.Cancelled, Instant.now())
    val payload = sessionCancelledPayload(session.sessionId, session.gameId)

    ds.withConnection { conn => conn.createStatement().execute("DROP TABLE history_event_outbox") }

    val result = repo.saveCancelWithOutbox(cancelled, Some(payload))

    result shouldBe a[Left[_, _]]
    repo.load(session.sessionId).value.lifecycle shouldBe SessionLifecycle.Created
    outbox.close()
  }

  it should "roll back the outbox row when the sessions table is absent" in {
    val path = tempPath()
    val ds = freshDs(path)
    val repo = SqliteSessionRepository(ds)
    val outbox = SqliteHistoryEventOutbox(path)

    val session = freshSession()
    repo.save(session).value
    val cancelled = GameSession.withLifecycle(session, SessionLifecycle.Cancelled, Instant.now())
    val payload = sessionCancelledPayload(session.sessionId, session.gameId)

    ds.withConnection { conn => conn.createStatement().execute("DROP TABLE sessions") }

    val result = repo.saveCancelWithOutbox(cancelled, Some(payload))

    result shouldBe a[Left[_, _]]
    outbox.pending(10).value shouldBe empty
    outbox.close()
  }
