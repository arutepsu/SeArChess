package chess.application.session.service

import chess.adapter.repository.InMemorySessionRepository
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests focused on [[SessionLifecycleService.getSessionByGameId]], the method added for the Phase 7 REST
  * adapter.
  *
  * Other [[SessionLifecycleService]] methods are exercised transitively by the REST mapper and route
  * integration paths; dedicated coverage for them can be added in Phase 8.
  */
class SessionLifecycleServiceSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def freshService =
    val repo = InMemorySessionRepository()
    (SessionLifecycleService(repo, _ => ()), repo)

  private def createSession(service: SessionLifecycleService): (GameId, GameSession) =
    val gameId = GameId.random()
    val session = service
      .createSession(
        gameId = gameId,
        mode = SessionMode.HumanVsHuman,
        whiteController = SideController.HumanLocal,
        blackController = SideController.HumanLocal
      )
      .value
    (gameId, session)

  // ── getSessionByGameId ────────────────────────────────────────────────────

  "SessionLifecycleService.getSessionByGameId" should "retrieve the session for a known GameId" in {
    val (service, _) = freshService
    val (gameId, saved) = createSession(service)
    val loaded = service.getSessionByGameId(gameId).value
    loaded.sessionId shouldBe saved.sessionId
    loaded.gameId shouldBe gameId
  }

  it should "return GameSessionNotFound for an unknown GameId" in {
    val (service, _) = freshService
    val unknownId = GameId.random()
    service.getSessionByGameId(unknownId).left.value shouldBe
      SessionError.GameSessionNotFound(unknownId)
  }

  it should "find the updated session after a lifecycle transition" in {
    val (service, _) = freshService
    val (gameId, sess) = createSession(service)
    service.updateLifecycle(sess.sessionId, SessionLifecycle.Active)
    val loaded = service.getSessionByGameId(gameId).value
    loaded.lifecycle shouldBe SessionLifecycle.Active
  }

