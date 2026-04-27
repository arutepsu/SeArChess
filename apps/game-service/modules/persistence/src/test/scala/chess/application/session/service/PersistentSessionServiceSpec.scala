package chess.application.session.service

import chess.adapter.repository.{
  InMemoryGameRepository,
  InMemorySessionGameStore,
  InMemorySessionRepository
}
import chess.application.port.repository.{GameRepository, RepositoryError, SessionGameStore}
import chess.application.query.session.SessionView
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.SessionId
import chess.domain.state.{GameState, GameStateFactory}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PersistentSessionServiceSpec extends AnyFlatSpec with Matchers with EitherValues:

  private final case class Fixture(
      service: PersistentSessionService,
      sessionRepo: InMemorySessionRepository,
      gameRepo: InMemoryGameRepository,
      store: SessionGameStore,
      sessionLifecycleService: SessionLifecycleService
  )

  private def fixture(
      storeOverride: Option[SessionGameStore] = None
  ): Fixture =
    val sessionRepo = InMemorySessionRepository()
    val gameRepo = InMemoryGameRepository()
    val defaultStore = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionLifecycleService = SessionLifecycleService(sessionRepo, _ => ())
    Fixture(
      service = PersistentSessionService(
        sessionRepository = sessionRepo,
        gameRepository = gameRepo,
        store = storeOverride.getOrElse(defaultStore),
        sessionLifecycleService = sessionLifecycleService
      ),
      sessionRepo = sessionRepo,
      gameRepo = gameRepo,
      store = storeOverride.getOrElse(defaultStore),
      sessionLifecycleService = sessionLifecycleService
    )

  private def persistedAggregate(
      sessionRepo: InMemorySessionRepository,
      gameRepo: InMemoryGameRepository,
      lifecycle: SessionLifecycle = SessionLifecycle.Created,
      state: GameState = GameStateFactory.initial()
  ): PersistentSessionAggregate =
    val base = GameSession.create(
      gameId = chess.application.session.model.SessionIds.GameId.random(),
      mode = SessionMode.HumanVsHuman,
      whiteController = SideController.HumanLocal,
      blackController = SideController.HumanLocal
    )
    val session =
      if lifecycle == SessionLifecycle.Created then base
      else GameSession.withLifecycle(base, lifecycle)
    sessionRepo.save(session).value
    gameRepo.save(session.gameId, state).value
    PersistentSessionAggregate(session, state)

  "PersistentSessionService.listActiveSessions" should "return active sessions as SessionView items" in {
    val fx = fixture()
    val active = persistedAggregate(fx.sessionRepo, fx.gameRepo, SessionLifecycle.Active)
    persistedAggregate(fx.sessionRepo, fx.gameRepo, SessionLifecycle.Cancelled)

    val result = fx.service.listActiveSessions().value

    result.map(_.sessionId) should contain only active.session.sessionId
    result.head shouldBe SessionView.fromSession(active.session)
  }

  "PersistentSessionService.loadAggregate" should "load the full persisted session aggregate" in {
    val fx = fixture()
    val saved = persistedAggregate(fx.sessionRepo, fx.gameRepo)

    val loaded = fx.service.loadAggregate(saved.session.sessionId).value

    loaded shouldBe saved
  }

  it should "return NotFound when the session does not exist" in {
    val fx = fixture()
    val unknown = SessionId.random()

    fx.service.loadAggregate(unknown).left.value shouldBe PersistentSessionError.NotFound(unknown)
  }

  it should "return AggregateInconsistent when the game state is missing for an existing session" in {
    val fx = fixture()
    val session = GameSession.create(
      gameId = chess.application.session.model.SessionIds.GameId.random(),
      mode = SessionMode.HumanVsHuman,
      whiteController = SideController.HumanLocal,
      blackController = SideController.HumanLocal
    )
    fx.sessionRepo.save(session).value

    val err = fx.service.loadAggregate(session.sessionId).left.value

    err shouldBe a[PersistentSessionError.AggregateInconsistent]
  }

  "PersistentSessionService.saveAggregate" should "persist the updated session and game state through SessionGameStore" in {
    val fx = fixture()
    val original = persistedAggregate(fx.sessionRepo, fx.gameRepo)
    val updatedSession = GameSession.withLifecycle(original.session, SessionLifecycle.Active)
    val updated = PersistentSessionAggregate(updatedSession, original.state)

    val saved = fx.service.saveAggregate(updated.session.sessionId, updated).value

    saved shouldBe updated
    fx.sessionRepo.load(updated.session.sessionId).value shouldBe updated.session
    fx.gameRepo.load(updated.session.gameId).value shouldBe updated.state
  }

  it should "return BadInput when the path sessionId does not match the body sessionId" in {
    val fx = fixture()
    val aggregate = persistedAggregate(fx.sessionRepo, fx.gameRepo)

    val err = fx.service.saveAggregate(SessionId.random(), aggregate).left.value

    err shouldBe a[PersistentSessionError.BadInput]
  }

  it should "return Conflict when the coordinated store reports a repository conflict" in {
    val fx = fixture(
      storeOverride = Some(FailingStore(Left(RepositoryError.Conflict("duplicate aggregate"))))
    )
    val aggregate = persistedAggregate(fx.sessionRepo, fx.gameRepo)

    fx.service.saveAggregate(aggregate.session.sessionId, aggregate).left.value shouldBe
      PersistentSessionError.Conflict("duplicate aggregate")
  }

  it should "return StorageFailure when the coordinated store reports a storage failure" in {
    val fx = fixture(
      storeOverride = Some(FailingStore(Left(RepositoryError.StorageFailure("disk full"))))
    )
    val aggregate = persistedAggregate(fx.sessionRepo, fx.gameRepo)

    fx.service.saveAggregate(aggregate.session.sessionId, aggregate).left.value shouldBe
      PersistentSessionError.StorageFailure("disk full")
  }

  "PersistentSessionService.cancelSession" should "delegate to the existing lifecycle service" in {
    val fx = fixture()
    val aggregate = persistedAggregate(fx.sessionRepo, fx.gameRepo)

    val cancelled = fx.service.cancelSession(aggregate.session.sessionId).value

    cancelled.lifecycle shouldBe SessionLifecycle.Cancelled
    fx.sessionRepo.load(aggregate.session.sessionId).value.lifecycle shouldBe SessionLifecycle.Cancelled
  }

  private final case class FailingStore(
      result: Either[RepositoryError, Unit]
  ) extends SessionGameStore:
    override def save(session: GameSession, state: GameState): Either[RepositoryError, Unit] = result

