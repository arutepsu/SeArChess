package chess.adapter.event

import chess.application.event.AppEvent
import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, DrawReason, GameStatus, Move, Position}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GameStreamEventSpec extends AnyFlatSpec with Matchers:

  private val sid = SessionId.random()
  private val gid = GameId.random()

  private val e2 = Position.from(4, 1).toOption.getOrElse(fail("invalid position (4,1)"))
  private val e4 = Position.from(4, 3).toOption.getOrElse(fail("invalid position (4,3)"))

  "GameStreamEvent.eventTypeTag" should "tag SessionCreated" in {
    val event = AppEvent.SessionCreated(sid, gid, SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal)
    GameStreamEvent.eventTypeTag(event) shouldBe Some("game.session.created.v1")
  }

  it should "tag MoveApplied" in {
    val event = AppEvent.MoveApplied(sid, gid, Move(e2, e4), Color.White)
    GameStreamEvent.eventTypeTag(event) shouldBe Some("game.move.applied.v1")
  }

  it should "tag GameFinished (checkmate)" in {
    val event = AppEvent.GameFinished(sid, gid, GameStatus.Checkmate(Color.White))
    GameStreamEvent.eventTypeTag(event) shouldBe Some("game.finished.v1")
  }

  it should "tag GameFinished (draw)" in {
    val event = AppEvent.GameFinished(sid, gid, GameStatus.Draw(DrawReason.Stalemate))
    GameStreamEvent.eventTypeTag(event) shouldBe Some("game.finished.v1")
  }

  it should "tag GameResigned" in {
    val event = AppEvent.GameResigned(sid, gid, Color.Black)
    GameStreamEvent.eventTypeTag(event) shouldBe Some("game.resigned.v1")
  }

  it should "tag SessionCancelled" in {
    val event = AppEvent.SessionCancelled(sid, gid)
    GameStreamEvent.eventTypeTag(event) shouldBe Some("game.session.cancelled.v1")
  }

  it should "return None for AITurnRequested" in {
    val event = AppEvent.AITurnRequested(sid, gid, Color.White)
    GameStreamEvent.eventTypeTag(event) shouldBe None
  }

  it should "return None for AITurnCompleted" in {
    val event = AppEvent.AITurnCompleted(sid, gid, Move(e2, e4))
    GameStreamEvent.eventTypeTag(event) shouldBe None
  }

  it should "return None for MoveRejected" in {
    val event = AppEvent.MoveRejected(sid, gid, Move(e2, e4), "illegal move")
    GameStreamEvent.eventTypeTag(event) shouldBe None
  }
