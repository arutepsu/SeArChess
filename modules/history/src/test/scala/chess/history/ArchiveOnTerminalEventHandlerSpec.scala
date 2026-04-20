package chess.history

import chess.adapter.event.CollectingEventPublisher
import chess.adapter.repository.{InMemoryGameRepository, InMemorySessionGameStore, InMemorySessionRepository}
import chess.application.DefaultGameService
import chess.application.event.AppEvent
import chess.application.query.game.GameClosure
import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.application.session.service.{SessionGameService, SessionService}
import chess.domain.model.{Color, DrawReason, GameStatus}
import chess.domain.state.GameStateFactory
import chess.notation.api.{ExportFailure, ExportResult, NotationFailure, NotationFormat}
import chess.notation.pgn.PgnNotationFacade
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests for [[ArchiveOnTerminalEventHandler.handle]].
 *
 *  Covers: all three terminal event types, non-terminal event passthrough,
 *  snapshot-not-closed (retryable race), snapshot-not-found, idempotent
 *  upsert, materialization failure, and persistence failure.
 */
class ArchiveOnTerminalEventHandlerSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  // ── Fixture ──────────────────────────────────────────────────────────────────

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
    )
    val archiveRepo = new InMemoryArchiveRepository
    val handler     = ArchiveOnTerminalEventHandler(svc, ArchiveMaterializer(), archiveRepo)
    (svc, gameRepo, archiveRepo, handler)

  private def createHvH(svc: DefaultGameService) =
    svc.createGame(SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal).value

  // ── GameFinished → archive upsert ────────────────────────────────────────────

  "ArchiveOnTerminalEventHandler.handle" should "archive a GameFinished (Checkmate) event" in {
    val (svc, gameRepo, archiveRepo, handler) = freshFixture()
    val (_, session) = createHvH(svc)

    gameRepo.save(session.gameId, GameStateFactory.initial().copy(status = GameStatus.Checkmate(Color.White))).value
    svc.cancelSession(session.sessionId).value

    val event  = AppEvent.GameFinished(session.sessionId, session.gameId, GameStatus.Checkmate(Color.White))
    val result = handler.handle(event).value

    result                         shouldBe defined
    result.value.closure           shouldBe GameClosure.Checkmate(Color.White)
    result.value.gameId            shouldBe session.gameId
    archiveRepo.findInMemory(session.gameId) shouldBe defined
  }

  it should "archive a GameFinished (Draw) event" in {
    val (svc, gameRepo, archiveRepo, handler) = freshFixture()
    val (_, session) = createHvH(svc)

    gameRepo.save(session.gameId, GameStateFactory.initial().copy(status = GameStatus.Draw(DrawReason.Stalemate))).value
    svc.cancelSession(session.sessionId).value

    val event  = AppEvent.GameFinished(session.sessionId, session.gameId, GameStatus.Draw(DrawReason.Stalemate))
    val result = handler.handle(event).value

    result.value.closure shouldBe GameClosure.Draw(DrawReason.Stalemate)
    archiveRepo.size     shouldBe 1
  }

  // ── GameResigned → archive upsert ────────────────────────────────────────────

  it should "archive a GameResigned event" in {
    val (svc, _, archiveRepo, handler) = freshFixture()
    val (_, session) = createHvH(svc)

    svc.resignGame(session.sessionId, Color.White).value

    val event  = AppEvent.GameResigned(session.sessionId, session.gameId, Color.Black)
    val result = handler.handle(event).value

    result.value.closure           shouldBe GameClosure.Resigned(Color.Black)
    result.value.gameId            shouldBe session.gameId
    archiveRepo.findInMemory(session.gameId) shouldBe defined
  }

  it should "include move history in the archived record when moves were played before resignation" in {
    val (svc, _, archiveRepo, handler) = freshFixture()
    val (_, session) = createHvH(svc)

    val e2e4 = chess.domain.model.Move(
      chess.domain.model.Position.fromAlgebraic("e2").value,
      chess.domain.model.Position.fromAlgebraic("e4").value
    )
    svc.submitMove(session.gameId, e2e4, SideController.HumanLocal).value
    svc.resignGame(session.sessionId, Color.Black).value

    val event  = AppEvent.GameResigned(session.sessionId, session.gameId, Color.White)
    val result = handler.handle(event).value

    result.value.pgn.value should include("1. e4")
  }

  // ── SessionCancelled → archive upsert ────────────────────────────────────────

  it should "archive a SessionCancelled event" in {
    val (svc, _, archiveRepo, handler) = freshFixture()
    val (_, session) = createHvH(svc)

    svc.cancelSession(session.sessionId).value

    val event  = AppEvent.SessionCancelled(session.sessionId, session.gameId)
    val result = handler.handle(event).value

    result.value.closure           shouldBe GameClosure.Cancelled
    archiveRepo.findInMemory(session.gameId) shouldBe defined
  }

  it should "set pgn to None for a cancelled game with no moves" in {
    val (svc, _, _, handler) = freshFixture()
    val (_, session) = createHvH(svc)

    svc.cancelSession(session.sessionId).value

    val record = handler.handle(AppEvent.SessionCancelled(session.sessionId, session.gameId)).value
    record.value.pgn shouldBe None
  }

  // ── Non-terminal event passthrough ───────────────────────────────────────────

  it should "return Right(None) for a non-terminal MoveApplied event" in {
    val (svc, _, archiveRepo, handler) = freshFixture()
    val (_, session) = createHvH(svc)

    val move  = chess.domain.model.Move(
      chess.domain.model.Position.fromAlgebraic("e2").value,
      chess.domain.model.Position.fromAlgebraic("e4").value
    )
    val event  = AppEvent.MoveApplied(session.sessionId, session.gameId, move, Color.White)
    val result = handler.handle(event).value

    result          shouldBe None
    archiveRepo.size shouldBe 0
  }

  it should "return Right(None) for a SessionCreated event" in {
    val (svc, _, _, handler) = freshFixture()
    val (_, session) = createHvH(svc)

    val event  = AppEvent.SessionCreated(session.sessionId, session.gameId,
                   SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal)
    val result = handler.handle(event).value

    result shouldBe None
  }

  // ── Snapshot not ready (retryable) ───────────────────────────────────────────

  it should "return SnapshotNotClosed when the session is still active at handle time" in {
    val (svc, _, _, handler) = freshFixture()
    val (_, session) = createHvH(svc)
    // Session is Active — NOT yet Finished; simulate event arriving before session close
    val event  = AppEvent.GameFinished(session.sessionId, session.gameId, GameStatus.Checkmate(Color.White))
    val result = handler.handle(event)

    result.left.value shouldBe ArchiveHandlerError.SnapshotNotClosed(session.gameId)
  }

  // ── Snapshot not found ───────────────────────────────────────────────────────

  it should "return SnapshotNotFound for an unknown game id" in {
    val (_, _, _, handler) = freshFixture()
    val unknownId = GameId.random()

    val event  = AppEvent.GameResigned(chess.application.session.model.SessionIds.SessionId.random(), unknownId, Color.White)
    val result = handler.handle(event)

    result.left.value shouldBe ArchiveHandlerError.SnapshotNotFound(unknownId)
  }

  // ── Idempotent upsert ────────────────────────────────────────────────────────

  it should "succeed on a second handle call for the same event (idempotent upsert)" in {
    val (svc, _, archiveRepo, handler) = freshFixture()
    val (_, session) = createHvH(svc)

    svc.resignGame(session.sessionId, Color.White).value

    val event = AppEvent.GameResigned(session.sessionId, session.gameId, Color.Black)
    handler.handle(event).value  // first call
    handler.handle(event).value  // second call — must not fail

    archiveRepo.size shouldBe 1  // upsert: one record, not two
  }

  // ── Materialization failure ───────────────────────────────────────────────────

  it should "return MaterializationFailed when the FEN exporter fails" in {
    val (svc, _, _, _) = freshFixture()
    val collector   = CollectingEventPublisher()
    val sessionRepo = new InMemorySessionRepository
    val gameRepo    = new InMemoryGameRepository
    val store       = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val svc2        = DefaultGameService(
      commands       = new SessionGameService(new SessionService(sessionRepo, collector), store, collector),
      sessionService = new SessionService(sessionRepo, collector),
      gameRepository = gameRepo,
      publisher      = collector
    )
    val (_, session) = svc2.createGame(SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal).value
    svc2.cancelSession(session.sessionId).value

    val failingFen: chess.domain.state.GameState => Either[NotationFailure, ExportResult] =
      _ => Left(ExportFailure.SerializationError("board", "injected FEN failure"))
    val failMat = ArchiveMaterializer.withExporters(failingFen, PgnNotationFacade.exportWithHeaders)
    val handler = ArchiveOnTerminalEventHandler(svc2, failMat, new InMemoryArchiveRepository)

    val event  = AppEvent.SessionCancelled(session.sessionId, session.gameId)
    val result = handler.handle(event)

    result.left.value shouldBe a[ArchiveHandlerError.MaterializationFailed]
  }

  // ── Persistence failure ───────────────────────────────────────────────────────

  it should "return PersistenceFailed when the repository fails on upsert" in {
    val (svc, _, _, _) = freshFixture()
    val collector   = CollectingEventPublisher()
    val sessionRepo = new InMemorySessionRepository
    val gameRepo    = new InMemoryGameRepository
    val store       = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val svc2        = DefaultGameService(
      commands       = new SessionGameService(new SessionService(sessionRepo, collector), store, collector),
      sessionService = new SessionService(sessionRepo, collector),
      gameRepository = gameRepo,
      publisher      = collector
    )
    val (_, session) = svc2.createGame(SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal).value
    svc2.cancelSession(session.sessionId).value

    val failingRepo = new ArchiveRepository:
      def upsert(record: ArchiveRecord): Either[ArchiveRepositoryError, Unit] =
        Left(ArchiveRepositoryError.StorageFailure("injected storage failure"))
      def findByGameId(gameId: chess.application.session.model.SessionIds.GameId): Either[ArchiveRepositoryError, Option[ArchiveRecord]] =
        Right(None)

    val handler = ArchiveOnTerminalEventHandler(svc2, ArchiveMaterializer(), failingRepo)
    val event   = AppEvent.SessionCancelled(session.sessionId, session.gameId)
    val result  = handler.handle(event)

    result.left.value shouldBe ArchiveHandlerError.PersistenceFailed("injected storage failure")
  }
