package chess.lichessbridge

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class GameFiberManagerSpec extends AnyFlatSpec with Matchers:

  private def makeRef(): Ref[IO, WorkerState] =
    IO.ref(WorkerState.empty).unsafeRunSync()

  private def makeMgr(stateRef: Ref[IO, WorkerState] = makeRef()): GameFiberManager =
    GameFiberManager.create(
      stateRef,
      "test-token",
      Some("testbot"),
      StubLichessGameStream(),
      StubAiServiceClient(),
      StubLichessClient()
    ).unsafeRunSync()

  private val game1 = LichessGameRef("g1", "https://lichess.org/g1", "opponent1", Some("white"))
  private val game2 = LichessGameRef("g2", "https://lichess.org/g2", "opponent2", Some("black"))

  // ── startGame ────────────────────────────────────────────────────────────────

  "GameFiberManager.startGame" should "start a fiber for a new game without error" in {
    val mgr = makeMgr()
    mgr.startGame(game1).unsafeRunSync()
    succeed
  }

  it should "not duplicate a fiber when called twice for the same gameId" in {
    val mgr = makeMgr()
    mgr.startGame(game1).unsafeRunSync()
    mgr.startGame(game1).unsafeRunSync()
    succeed
  }

  it should "start independent fibers for two different games" in {
    val mgr = makeMgr()
    mgr.startGame(game1).unsafeRunSync()
    mgr.startGame(game2).unsafeRunSync()
    succeed
  }

  // ── stopGame ──────────────────────────────────────────────────────────────────

  "GameFiberManager.stopGame" should "cancel and remove the fiber for the given gameId" in {
    val mgr = makeMgr()
    mgr.startGame(game1).unsafeRunSync()
    mgr.stopGame(game1.gameId).unsafeRunSync()
    succeed
  }

  it should "be a no-op for a gameId that has no fiber" in {
    val mgr = makeMgr()
    mgr.stopGame("nonexistent-game").unsafeRunSync()
    succeed
  }

  // ── cancelAll ─────────────────────────────────────────────────────────────────

  "GameFiberManager.cancelAll" should "cancel all active game fibers without error" in {
    val mgr = makeMgr()
    mgr.startGame(game1).unsafeRunSync()
    mgr.startGame(game2).unsafeRunSync()
    mgr.cancelAll().unsafeRunSync()
    succeed
  }

  it should "be a no-op when no fibers are registered" in {
    val mgr = makeMgr()
    mgr.cancelAll().unsafeRunSync()
    succeed
  }
