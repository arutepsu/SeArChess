package chess.adapter.event

import chess.application.event.AppEvent
import chess.application.session.model.{SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, DrawReason, GameStatus, Move, Position}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NoOpEventPublisherSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val sid = SessionId.random()
  private val gid = GameId.random()
  private val e2 = Position.from(4, 1).value // e2
  private val e4 = Position.from(4, 3).value // e4

  "NoOpEventPublisher" should "silently discard every AppEvent variant without throwing" in {
    val events: List[AppEvent] = List(
      AppEvent.SessionCreated(
        sid,
        gid,
        SessionMode.HumanVsHuman,
        SideController.HumanLocal,
        SideController.HumanLocal
      ),
      AppEvent.SessionLifecycleChanged(sid, gid, SessionLifecycle.Created, SessionLifecycle.Active),
      AppEvent.MoveApplied(sid, gid, Move(e2, e4), Color.White),
      AppEvent.PromotionPending(sid, gid),
      AppEvent.GameFinished(sid, gid, GameStatus.Checkmate(Color.White)),
      AppEvent.GameFinished(sid, gid, GameStatus.Draw(DrawReason.Stalemate)),
      AppEvent.AITurnRequested(sid, gid, Color.Black),
      AppEvent.AITurnCompleted(sid, gid, Move(e2, e4)),
      AppEvent.AITurnFailed(sid, gid, "engine failure"),
      AppEvent.MoveRejected(sid, gid, Move(e2, e4), "illegal move"),
      AppEvent.GameResigned(sid, gid, Color.Black),
      AppEvent.SessionCancelled(sid, gid)
    )
    events.foreach { e =>
      noException should be thrownBy NoOpEventPublisher.publish(e)
    }
  }

  it should "remain a no-op across repeated calls with the same event" in {
    val event = AppEvent.MoveApplied(sid, gid, Move(e2, e4), Color.White)
    noException should be thrownBy {
      NoOpEventPublisher.publish(event)
      NoOpEventPublisher.publish(event)
      NoOpEventPublisher.publish(event)
    }
  }
