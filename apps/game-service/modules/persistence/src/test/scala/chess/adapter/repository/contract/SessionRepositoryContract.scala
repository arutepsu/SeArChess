package chess.adapter.repository.contract

import chess.application.port.repository.{RepositoryError, SessionRepository}
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.time.Instant

trait SessionRepositoryContract extends AnyFlatSpecLike with Matchers with EitherValues:

  def repositoryName: String

  def freshRepository(): SessionRepository

  private val now = Instant.parse("2024-01-01T00:00:00Z")

  private def freshSession(gameId: GameId = GameId.random()): GameSession =
    GameSession.create(
      gameId = gameId,
      mode = SessionMode.HumanVsHuman,
      whiteController = SideController.HumanLocal,
      blackController = SideController.HumanLocal,
      now = now
    )

  repositoryName should "round-trip the exact GameSession value by SessionId" in {
    val repo = freshRepository()
    val session = freshSession().copy(
      whiteController = SideController.AI(Some("stockfish-15")),
      blackController = SideController.AI(None)
    )

    repo.save(session).value

    repo.load(session.sessionId).value shouldBe session
  }

  it should "update an existing session with the same SessionId" in {
    val repo = freshRepository()
    val session = freshSession()
    val updated = session.copy(
      lifecycle = SessionLifecycle.Active,
      updatedAt = Instant.parse("2024-01-01T00:01:00Z")
    )

    repo.save(session).value
    repo.save(updated).value

    repo.load(session.sessionId).value shouldBe updated
  }

  it should "return NotFound for an unknown SessionId" in {
    val repo = freshRepository()
    val unknown = SessionId.random()

    repo.load(unknown).left.value shouldBe RepositoryError.NotFound(unknown.value.toString)
  }

  it should "load the session that owns a GameId" in {
    val repo = freshRepository()
    val session = freshSession()

    repo.save(session).value

    repo.loadByGameId(session.gameId).value shouldBe session
  }

  it should "return NotFound for an unknown GameId" in {
    val repo = freshRepository()
    val unknown = GameId.random()

    repo.loadByGameId(unknown).left.value shouldBe RepositoryError.NotFound(unknown.value.toString)
  }

  it should "reject a different session with an existing GameId" in {
    val repo = freshRepository()
    val gameId = GameId.random()
    val first = freshSession(gameId)
    val conflicting = first.copy(sessionId = SessionId.random())

    repo.save(first).value

    repo.save(conflicting).left.value shouldBe a[RepositoryError.Conflict]
    repo.load(first.sessionId).value shouldBe first
    repo.loadByGameId(gameId).value shouldBe first
  }

  it should "allow the same session to be saved repeatedly for its GameId" in {
    val repo = freshRepository()
    val session = freshSession()
    val updated = session.copy(lifecycle = SessionLifecycle.Active)

    repo.save(session).value
    repo.save(updated).value

    repo.loadByGameId(session.gameId).value shouldBe updated
  }

  it should "return an empty active-session list when no sessions exist" in {
    val repo = freshRepository()

    repo.listActive().value shouldBe List.empty
  }

  it should "include non-terminal sessions and exclude terminal sessions from listActive" in {
    val repo = freshRepository()
    val created = freshSession().copy(lifecycle = SessionLifecycle.Created)
    val active = freshSession().copy(lifecycle = SessionLifecycle.Active)
    val awaitingPromotion = freshSession().copy(lifecycle = SessionLifecycle.AwaitingPromotion)
    val finished = freshSession().copy(lifecycle = SessionLifecycle.Finished)
    val cancelled = freshSession().copy(lifecycle = SessionLifecycle.Cancelled)

    List(created, active, awaitingPromotion, finished, cancelled).foreach(repo.save(_).value)

    repo.listActive().value.map(_.sessionId).toSet shouldBe
      Set(created.sessionId, active.sessionId, awaitingPromotion.sessionId)
  }
