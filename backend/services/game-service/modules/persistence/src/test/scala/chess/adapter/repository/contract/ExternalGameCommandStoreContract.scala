package chess.adapter.repository.contract

import chess.application.external.{BindingId, BindingStatus, ExternalGameBinding}
import chess.application.port.repository.{
  ExternalGameBindingRepository,
  ExternalGameCommandStore,
  GameRepository,
  RepositoryError,
  SessionRepository
}
import chess.application.session.model.{ExternalPlatform, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, GameStatus, Move, Position}
import chess.domain.state.{CastlingRights, GameState, GameStateFactory}
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import java.time.Instant

trait ExternalGameCommandStoreContract extends AnyFlatSpecLike with Matchers with EitherValues with OptionValues:

  final case class CommandStoreFixture(
      sessionRepository: SessionRepository,
      gameRepository: GameRepository,
      bindingRepository: ExternalGameBindingRepository,
      commandStore: ExternalGameCommandStore
  )

  def storeName: String
  def freshCommandStore(): CommandStoreFixture

  private val now      = Instant.parse("2026-01-01T12:00:00Z")
  private val platform = ExternalPlatform.Lichess

  private def freshSession(gameId: GameId = GameId.random()) =
    chess.application.session.model.GameSession.create(
      gameId          = gameId,
      mode            = SessionMode.AiVsExternal,
      whiteController = SideController.AI(),
      blackController = SideController.External(platform, "opponent"),
      now             = now
    )

  private def freshState: GameState = GameStateFactory.initial()

  private def freshBinding(sessionId: SessionId, gameId: GameId, externalId: String = "game-abc") =
    ExternalGameBinding(
      bindingId        = BindingId.random(),
      platform         = platform,
      externalGameId   = externalId,
      internalGameId   = gameId,
      sessionId        = sessionId,
      ourActorId       = "searchess-bot",
      status           = BindingStatus.Active,
      lastProcessedPly = 0,
      createdAt        = now,
      updatedAt        = now
    )

  // ── createWithBinding ─────────────────────────────────────────────────────

  storeName should "createWithBinding writes session + game state + binding atomically" in {
    val f       = freshCommandStore()
    val session = freshSession()
    val state   = freshState
    val binding = freshBinding(session.sessionId, session.gameId)

    f.commandStore.createWithBinding(session, state, binding).value

    f.sessionRepository.load(session.sessionId).value shouldBe session
    f.gameRepository.load(session.gameId).isRight shouldBe true
    f.bindingRepository.findByExternalId(platform, binding.externalGameId).value shouldBe Some(binding)
  }

  it should "return Conflict on duplicate externalGameId and roll back session/game writes" in {
    val f        = freshCommandStore()
    val session1 = freshSession()
    val binding1 = freshBinding(session1.sessionId, session1.gameId, "dup-game")

    f.commandStore.createWithBinding(session1, freshState, binding1).value

    val session2 = freshSession()
    val binding2 = freshBinding(session2.sessionId, session2.gameId, "dup-game")

    f.commandStore.createWithBinding(session2, freshState, binding2).left.value shouldBe
      a[RepositoryError.Conflict]

    // session2 and its game state should not be visible (rolled back)
    f.sessionRepository.load(session2.sessionId).left.value shouldBe
      RepositoryError.NotFound(session2.sessionId.value.toString)
    f.gameRepository.load(session2.gameId).left.value shouldBe
      RepositoryError.NotFound(session2.gameId.value.toString)
  }

  // ── saveMoveWithPlyAdvance ────────────────────────────────────────────────

  it should "saveMoveWithPlyAdvance updates session + game state + lastProcessedPly atomically" in {
    val f       = freshCommandStore()
    val session = freshSession()
    val state   = freshState
    val binding = freshBinding(session.sessionId, session.gameId)

    f.commandStore.createWithBinding(session, state, binding).value

    val updatedState = state.copy(fullmoveNumber = 2)
    val updatedSession = session.copy(
      lifecycle = chess.application.session.model.SessionLifecycle.Active,
      updatedAt = now
    )
    f.commandStore.saveMoveWithPlyAdvance(updatedSession, updatedState, binding.bindingId, 1, now).value

    val stored = f.bindingRepository.findById(binding.bindingId).value.value
    stored.lastProcessedPly shouldBe 1
    f.gameRepository.load(session.gameId).isRight shouldBe true
  }

  it should "return Conflict for stale ply (newPly <= lastProcessedPly)" in {
    val f       = freshCommandStore()
    val session = freshSession()
    val binding = freshBinding(session.sessionId, session.gameId)
    val initialState = freshState
    f.commandStore.createWithBinding(session, initialState, binding).value

    // Advance to ply 2
    f.commandStore.saveMoveWithPlyAdvance(session, initialState, binding.bindingId, 2, now).value

    // Attempt to advance to ply 1 (stale)
    val staleSession = session.copy(
      lifecycle = chess.application.session.model.SessionLifecycle.Finished,
      updatedAt = now.plusSeconds(1)
    )
    val staleState = initialState.copy(fullmoveNumber = initialState.fullmoveNumber + 10)
    f.commandStore.saveMoveWithPlyAdvance(staleSession, staleState, binding.bindingId, 1, now.plusSeconds(1))
      .left.value shouldBe a[RepositoryError.Conflict]
    f.sessionRepository.load(session.sessionId).value shouldBe session
    f.gameRepository.load(session.gameId).value shouldBe initialState
    f.bindingRepository.findById(binding.bindingId).value.value.lastProcessedPly shouldBe 2

    // Attempt to advance to same ply 2 (not strictly greater)
    f.commandStore.saveMoveWithPlyAdvance(staleSession, staleState, binding.bindingId, 2, now.plusSeconds(2))
      .left.value shouldBe a[RepositoryError.Conflict]
    f.sessionRepository.load(session.sessionId).value shouldBe session
    f.gameRepository.load(session.gameId).value shouldBe initialState
    f.bindingRepository.findById(binding.bindingId).value.value.lastProcessedPly shouldBe 2
  }

  // ── saveFinishedWithBinding ───────────────────────────────────────────────

  it should "saveFinishedWithBinding marks binding Finished and updates session" in {
    val f       = freshCommandStore()
    val session = freshSession()
    val binding = freshBinding(session.sessionId, session.gameId)
    f.commandStore.createWithBinding(session, freshState, binding).value

    val finishedSession = session.copy(
      lifecycle = chess.application.session.model.SessionLifecycle.Finished,
      updatedAt = now
    )
    f.commandStore.saveFinishedWithBinding(finishedSession, freshState, binding.bindingId, now).value

    val stored = f.bindingRepository.findById(binding.bindingId).value.value
    stored.status shouldBe BindingStatus.Finished
    f.sessionRepository.load(session.sessionId).value.lifecycle shouldBe
      chess.application.session.model.SessionLifecycle.Finished
  }
