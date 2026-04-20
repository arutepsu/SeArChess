package chess.application

import chess.adapter.event.CollectingEventPublisher
<<<<<<< HEAD:apps/game-service/modules/persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala
import chess.adapter.repository.{
  InMemoryGameRepository,
  InMemorySessionGameStore,
  InMemorySessionRepository
}
import chess.application.query.game.{GameArchiveSnapshot, GameClosure}
import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.application.session.service.{SessionGameCommandService, SessionLifecycleService}
=======
import chess.adapter.repository.{InMemoryGameRepository, InMemorySessionGameStore, InMemorySessionRepository}
import chess.application.query.game.{GameArchiveSnapshot, GameClosure}
import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.application.session.service.{SessionGameService, SessionService}
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services):modules/adapter-persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala
import chess.domain.model.{Color, DrawReason, GameStatus}
import chess.domain.state.GameStateFactory
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests for [[DefaultGameService.getArchiveSnapshot]].
<<<<<<< HEAD:apps/game-service/modules/persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala
  *
  * Terminal state setup:
  *   - Resigned — via `svc.resignGame`, which correctly sets `GameStatus.Resigned`
  *   - Cancelled — via `svc.cancelSession`, which leaves state `Ongoing`
  *   - Checkmate / Draw — state saved directly to `gameRepo` then session closed via
  *     `cancelSession`; snapshot derives closure from `state.status`
  */
class GameArchiveSnapshotSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def freshFixture() =
    val collector = CollectingEventPublisher()
    val sessionRepo = new InMemorySessionRepository
    val gameRepo = new InMemoryGameRepository
    val store = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val svc = DefaultGameService(
      commands =
        new SessionGameCommandService(new SessionLifecycleService(sessionRepo, collector), store, collector),
      sessionLifecycleService = new SessionLifecycleService(sessionRepo, collector),
      gameRepository = gameRepo,
      publisher = collector
=======
 *
 *  Terminal state setup:
 *  - Resigned  — via `svc.resignGame`, which correctly sets `GameStatus.Resigned`
 *  - Cancelled — via `svc.cancelSession`, which leaves state `Ongoing`
 *  - Checkmate / Draw — state saved directly to `gameRepo` then session closed
 *    via `cancelSession`; snapshot derives closure from `state.status`
 */
class GameArchiveSnapshotSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def freshFixture() =
    val collector   = CollectingEventPublisher()
    val sessionRepo = new InMemorySessionRepository
    val gameRepo    = new InMemoryGameRepository
    val store       = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val svc         = DefaultGameService(
      commands       = new SessionGameService(new SessionService(sessionRepo, collector), store, collector),
      sessionService = new SessionService(sessionRepo, collector),
      gameRepository = gameRepo,
      publisher      = collector
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services):modules/adapter-persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala
    )
    (svc, gameRepo)

  private def createHvH(svc: DefaultGameService) =
<<<<<<< HEAD:apps/game-service/modules/persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala
    svc
      .createGame(SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal)
      .value
=======
    svc.createGame(SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal).value
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services):modules/adapter-persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala

  // ── Resigned ───────────────────────────────────────────────────────────────

  "DefaultGameService.getArchiveSnapshot" should "return Resigned closure when White resigns" in {
    val (svc, _) = freshFixture()
    val (_, session) = createHvH(svc)

    svc.resignGame(session.sessionId, Color.White).value

    val snap = svc.getArchiveSnapshot(session.gameId).value
<<<<<<< HEAD:apps/game-service/modules/persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala
    snap.closure shouldBe GameClosure.Resigned(Color.Black)
    snap.gameId shouldBe session.gameId
    snap.sessionId shouldBe session.sessionId
    snap.mode shouldBe SessionMode.HumanVsHuman
=======
    snap.closure          shouldBe GameClosure.Resigned(Color.Black)
    snap.gameId           shouldBe session.gameId
    snap.sessionId        shouldBe session.sessionId
    snap.mode             shouldBe SessionMode.HumanVsHuman
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services):modules/adapter-persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala
  }

  it should "return Resigned(White) closure when Black resigns" in {
    val (svc, _) = freshFixture()
    val (_, session) = createHvH(svc)

    svc.resignGame(session.sessionId, Color.Black).value

    svc.getArchiveSnapshot(session.gameId).value.closure shouldBe GameClosure.Resigned(Color.White)
  }

  it should "capture move history in the final state for a resigned game" in {
    val (svc, _) = freshFixture()
    val (_, session) = createHvH(svc)
    val e2e4 = chess.domain.model.Move(
      chess.domain.model.Position.fromAlgebraic("e2").value,
      chess.domain.model.Position.fromAlgebraic("e4").value
    )
    svc.submitMove(session.gameId, e2e4, SideController.HumanLocal).value
    svc.resignGame(session.sessionId, Color.Black).value

    val snap = svc.getArchiveSnapshot(session.gameId).value
    snap.finalState.moveHistory should have size 1
<<<<<<< HEAD:apps/game-service/modules/persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala
    snap.finalState.moveHistory.head.from shouldBe chess.domain.model.Position
      .fromAlgebraic("e2")
      .value
=======
    snap.finalState.moveHistory.head.from shouldBe chess.domain.model.Position.fromAlgebraic("e2").value
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services):modules/adapter-persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala
  }

  // ── Cancelled ──────────────────────────────────────────────────────────────

  it should "return Cancelled closure when session is administratively cancelled" in {
    val (svc, _) = freshFixture()
    val (_, session) = createHvH(svc)

    svc.cancelSession(session.sessionId).value

    val snap = svc.getArchiveSnapshot(session.gameId).value
    snap.closure shouldBe GameClosure.Cancelled
  }

  it should "capture the in-progress position in the final state for a cancelled game" in {
    val (svc, _) = freshFixture()
    val (_, session) = createHvH(svc)
    svc.cancelSession(session.sessionId).value

    val snap = svc.getArchiveSnapshot(session.gameId).value
<<<<<<< HEAD:apps/game-service/modules/persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala
    snap.finalState.board should have size 32
    snap.finalState.moveHistory shouldBe empty
=======
    snap.finalState.board         should have size 32
    snap.finalState.moveHistory   shouldBe empty
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services):modules/adapter-persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala
  }

  // ── Checkmate ──────────────────────────────────────────────────────────────

  it should "return Checkmate closure when game state is Checkmate" in {
    val (svc, gameRepo) = freshFixture()
    val (_, session) = createHvH(svc)

    val checkmateState = GameStateFactory.initial().copy(status = GameStatus.Checkmate(Color.White))
    gameRepo.save(session.gameId, checkmateState).value
<<<<<<< HEAD:apps/game-service/modules/persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala
    svc.cancelSession(session.sessionId).value // close the session
=======
    svc.cancelSession(session.sessionId).value  // close the session
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services):modules/adapter-persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala

    val snap = svc.getArchiveSnapshot(session.gameId).value
    snap.closure shouldBe GameClosure.Checkmate(Color.White)
  }

  // ── Draw ───────────────────────────────────────────────────────────────────

  it should "return Draw closure when game state is Draw(Stalemate)" in {
    val (svc, gameRepo) = freshFixture()
    val (_, session) = createHvH(svc)

    val drawState = GameStateFactory.initial().copy(status = GameStatus.Draw(DrawReason.Stalemate))
    gameRepo.save(session.gameId, drawState).value
    svc.cancelSession(session.sessionId).value

    val snap = svc.getArchiveSnapshot(session.gameId).value
    snap.closure shouldBe GameClosure.Draw(DrawReason.Stalemate)
  }

  // ── Active session ─────────────────────────────────────────────────────────

  it should "return GameNotClosed for a session still in progress" in {
    val (svc, _) = freshFixture()
    val (_, session) = createHvH(svc)

<<<<<<< HEAD:apps/game-service/modules/persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala
    svc.getArchiveSnapshot(session.gameId).left.value shouldBe ArchiveError.GameNotClosed(
      session.gameId
    )
=======
    svc.getArchiveSnapshot(session.gameId).left.value shouldBe ArchiveError.GameNotClosed(session.gameId)
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services):modules/adapter-persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala
  }

  // ── Unknown id ─────────────────────────────────────────────────────────────

  it should "return GameNotFound for an unknown game id" in {
    val (svc, _) = freshFixture()
    val unknown = GameId.random()

    svc.getArchiveSnapshot(unknown).left.value shouldBe ArchiveError.GameNotFound(unknown)
  }

  // ── Session metadata ───────────────────────────────────────────────────────

  it should "include correct controller metadata in the snapshot" in {
<<<<<<< HEAD:apps/game-service/modules/persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala
    val collector = CollectingEventPublisher()
    val sessionRepo = new InMemorySessionRepository
    val gameRepo = new InMemoryGameRepository
    val store = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val svc = DefaultGameService(
      commands =
        new SessionGameCommandService(new SessionLifecycleService(sessionRepo, collector), store, collector),
      sessionLifecycleService = new SessionLifecycleService(sessionRepo, collector),
      gameRepository = gameRepo,
      publisher = collector
    )

    val (_, session) = svc
      .createGame(
        SessionMode.HumanVsAI,
        SideController.HumanLocal,
        SideController.AI()
      )
      .value
    svc.cancelSession(session.sessionId).value

    val snap = svc.getArchiveSnapshot(session.gameId).value
    snap.mode shouldBe SessionMode.HumanVsAI
    snap.whiteController shouldBe SideController.HumanLocal
    snap.blackController shouldBe SideController.AI()
=======
    val collector   = CollectingEventPublisher()
    val sessionRepo = new InMemorySessionRepository
    val gameRepo    = new InMemoryGameRepository
    val store       = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val svc         = DefaultGameService(
      commands       = new SessionGameService(new SessionService(sessionRepo, collector), store, collector),
      sessionService = new SessionService(sessionRepo, collector),
      gameRepository = gameRepo,
      publisher      = collector
    )

    val (_, session) = svc.createGame(
      SessionMode.HumanVsAI, SideController.HumanLocal, SideController.AI()
    ).value
    svc.cancelSession(session.sessionId).value

    val snap = svc.getArchiveSnapshot(session.gameId).value
    snap.mode             shouldBe SessionMode.HumanVsAI
    snap.whiteController  shouldBe SideController.HumanLocal
    snap.blackController  shouldBe SideController.AI()
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services):modules/adapter-persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala
  }

  it should "set closedAt from session updatedAt at close time" in {
    val (svc, _) = freshFixture()
    val (_, session) = createHvH(svc)
    val closeTime = session.createdAt.plusSeconds(60)
    svc.resignGame(session.sessionId, Color.White, now = closeTime).value

    val snap = svc.getArchiveSnapshot(session.gameId).value
    snap.createdAt shouldBe session.createdAt
<<<<<<< HEAD:apps/game-service/modules/persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala
    snap.closedAt shouldBe closeTime
    snap.closedAt should be > snap.createdAt
=======
    snap.closedAt  shouldBe closeTime
    snap.closedAt  should be > snap.createdAt
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services):modules/adapter-persistence/src/test/scala/chess/application/GameArchiveSnapshotSpec.scala
  }
