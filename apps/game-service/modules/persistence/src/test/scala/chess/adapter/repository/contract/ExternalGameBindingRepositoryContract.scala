package chess.adapter.repository.contract

import chess.application.external.{BindingId, BindingStatus, ExternalGameBinding}
import chess.application.port.repository.{
  ExternalGameBindingRepository,
  GameRepository,
  RepositoryError,
  SessionRepository
}
import chess.application.session.model.{ExternalPlatform, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.state.GameStateFactory
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import java.time.Instant

trait ExternalGameBindingRepositoryContract
    extends AnyFlatSpecLike
    with Matchers
    with EitherValues
    with OptionValues:

  /** Fixture that bundles the binding repo together with the session/game repos needed to satisfy
    * FK constraints in Postgres (SQLite ignores them; having the backing repos present also makes
    * the contract trivially database-agnostic).
    */
  final case class BindingRepoFixture(
      sessionRepository: SessionRepository,
      gameRepository: GameRepository,
      bindingRepository: ExternalGameBindingRepository
  )

  def repoName: String
  def freshBindingFixture(): BindingRepoFixture

  private val now      = Instant.parse("2026-01-01T12:00:00Z")
  private val platform = ExternalPlatform.Lichess

  private def freshBinding(
      sessionId: SessionId,
      gameId: GameId,
      externalId: String = "game-abc"
  ) =
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

  /** Insert the session and game-state rows that the Postgres FK constraints require. */
  private def insertBacking(
      f: BindingRepoFixture,
      sessionId: SessionId,
      gameId: GameId
  ): Unit =
    val session = chess.application.session.model.GameSession.create(
      gameId          = gameId,
      mode            = SessionMode.AiVsExternal,
      whiteController = SideController.AI(),
      blackController = SideController.External(platform, "opponent"),
      now             = now
    ).copy(sessionId = sessionId)
    f.sessionRepository.save(session).value
    f.gameRepository.save(gameId, GameStateFactory.initial()).value

  // ── create + findByExternalId ─────────────────────────────────────────────

  repoName should "create and retrieve a binding by (platform, externalGameId)" in {
    val f         = freshBindingFixture()
    val sessionId = SessionId.random()
    val gameId    = GameId.random()
    insertBacking(f, sessionId, gameId)
    val binding = freshBinding(sessionId, gameId)
    f.bindingRepository.create(binding).value
    f.bindingRepository.findByExternalId(platform, binding.externalGameId).value shouldBe Some(binding)
  }

  it should "return None for an unknown (platform, externalGameId)" in {
    val f = freshBindingFixture()
    f.bindingRepository.findByExternalId(platform, "no-such-game").value shouldBe None
  }

  it should "return Conflict on duplicate (platform, externalGameId)" in {
    val f         = freshBindingFixture()
    val sessionId = SessionId.random()
    val gameId    = GameId.random()
    insertBacking(f, sessionId, gameId)
    val b1 = freshBinding(sessionId, gameId, "dup-game")
    val s2 = SessionId.random()
    val g2 = GameId.random()
    insertBacking(f, s2, g2)
    val b2 = freshBinding(s2, g2, "dup-game")
    f.bindingRepository.create(b1).value
    f.bindingRepository.create(b2).left.value shouldBe a[RepositoryError.Conflict]
  }

  // ── findById ──────────────────────────────────────────────────────────────

  it should "find a binding by its BindingId" in {
    val f         = freshBindingFixture()
    val sessionId = SessionId.random()
    val gameId    = GameId.random()
    insertBacking(f, sessionId, gameId)
    val binding = freshBinding(sessionId, gameId)
    f.bindingRepository.create(binding).value
    f.bindingRepository.findById(binding.bindingId).value shouldBe Some(binding)
  }

  it should "return None for an unknown BindingId" in {
    val f = freshBindingFixture()
    f.bindingRepository.findById(BindingId.random()).value shouldBe None
  }

  // ── markFinished ──────────────────────────────────────────────────────────

  it should "markFinished updates status to Finished" in {
    val f         = freshBindingFixture()
    val sessionId = SessionId.random()
    val gameId    = GameId.random()
    insertBacking(f, sessionId, gameId)
    val binding = freshBinding(sessionId, gameId)
    f.bindingRepository.create(binding).value
    f.bindingRepository.markFinished(binding.bindingId, now).value
    val updated = f.bindingRepository.findById(binding.bindingId).value.value
    updated.status shouldBe BindingStatus.Finished
  }

  it should "markFinished returns NotFound for unknown bindingId" in {
    val f = freshBindingFixture()
    f.bindingRepository.markFinished(BindingId.random(), now).left.value shouldBe
      a[RepositoryError.NotFound]
  }

  // ── markFailed ────────────────────────────────────────────────────────────

  it should "markFailed persists the failure reason" in {
    val f         = freshBindingFixture()
    val sessionId = SessionId.random()
    val gameId    = GameId.random()
    insertBacking(f, sessionId, gameId)
    val binding = freshBinding(sessionId, gameId)
    f.bindingRepository.create(binding).value
    f.bindingRepository.markFailed(binding.bindingId, "something went wrong", now).value
    val updated = f.bindingRepository.findById(binding.bindingId).value.value
    updated.status shouldBe BindingStatus.Failed("something went wrong")
  }

  it should "markFailed returns NotFound for unknown bindingId" in {
    val f = freshBindingFixture()
    f.bindingRepository.markFailed(BindingId.random(), "error", now).left.value shouldBe
      a[RepositoryError.NotFound]
  }
