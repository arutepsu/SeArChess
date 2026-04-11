package chess.adapter.websocket

import chess.application.event.AppEvent
import chess.application.session.model.{SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, DrawReason, GameStatus, Move, PieceType, Position}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, OptionValues}

class WebSocketMessageMapperSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  private val sid  = SessionId.random()
  private val gid  = GameId.random()
  private val e2   = Position.from(4, 1).value  // e2
  private val e4   = Position.from(4, 3).value  // e4
  private val e7   = Position.from(4, 6).value  // e7
  private val e8   = Position.from(4, 7).value  // e8

  private def parse(event: AppEvent) = ujson.read(WebSocketMessageMapper.toMessage(event))

  // ── common fields ─────────────────────────────────────────────────────────

  "WebSocketMessageMapper.toMessage" should "include eventType, sessionId, and gameId in every message" in {
    val json = parse(AppEvent.PromotionPending(sid, gid))
    json("eventType").str shouldBe "PromotionPending"
    json("sessionId").str shouldBe sid.value.toString
    json("gameId").str    shouldBe gid.value.toString
  }

  it should "produce valid JSON for all event types" in {
    val events: List[AppEvent] = List(
      AppEvent.SessionCreated(sid, gid, SessionMode.HumanVsAI, SideController.HumanLocal, SideController.AI()),
      AppEvent.SessionLifecycleChanged(sid, gid, SessionLifecycle.Created, SessionLifecycle.Active),
      AppEvent.MoveApplied(sid, gid, Move(e2, e4), Color.White),
      AppEvent.PromotionPending(sid, gid),
      AppEvent.GameFinished(sid, gid, GameStatus.Checkmate(Color.White)),
      AppEvent.AITurnRequested(sid, gid, Color.Black),
      AppEvent.AITurnCompleted(sid, gid, Move(e2, e4)),
      AppEvent.AITurnFailed(sid, gid, "engine failure: timeout")
    )
    events.foreach { e =>
      noException should be thrownBy ujson.read(WebSocketMessageMapper.toMessage(e))
    }
  }

  // ── SessionCreated ─────────────────────────────────────────────────────────

  "SessionCreated" should "include mode, whiteController, blackController" in {
    val json = parse(AppEvent.SessionCreated(
      sid, gid, SessionMode.HumanVsAI, SideController.HumanLocal, SideController.AI()))
    json("eventType").str        shouldBe "SessionCreated"
    json("mode").str             shouldBe "HumanVsAI"
    json("whiteController").str  shouldBe "HumanLocal"
    json("blackController").str  should startWith("AI")
  }

  // ── SessionLifecycleChanged ────────────────────────────────────────────────

  "SessionLifecycleChanged" should "include from and to lifecycle values" in {
    val json = parse(AppEvent.SessionLifecycleChanged(sid, gid, SessionLifecycle.Active, SessionLifecycle.Finished))
    json("from").str shouldBe "Active"
    json("to").str   shouldBe "Finished"
  }

  // ── MoveApplied ────────────────────────────────────────────────────────────

  "MoveApplied" should "include move with from/to in algebraic notation" in {
    val json = parse(AppEvent.MoveApplied(sid, gid, Move(e2, e4), Color.White))
    json("currentPlayer").str    shouldBe "White"
    json("move")("from").str     shouldBe "e2"
    json("move")("to").str       shouldBe "e4"
  }

  it should "include promotion field when present" in {
    val json = parse(AppEvent.MoveApplied(sid, gid, Move(e7, e8, Some(PieceType.Queen)), Color.White))
    json("move")("promotion").str shouldBe "Queen"
  }

  it should "omit promotion field when absent" in {
    val json = parse(AppEvent.MoveApplied(sid, gid, Move(e2, e4), Color.White))
    json("move").obj.contains("promotion") shouldBe false
  }

  // ── GameFinished ───────────────────────────────────────────────────────────

  "GameFinished" should "include status=Checkmate and winner for checkmate" in {
    val json = parse(AppEvent.GameFinished(sid, gid, GameStatus.Checkmate(Color.Black)))
    json("status").str shouldBe "Checkmate"
    json("winner").str shouldBe "Black"
  }

  it should "include status=Draw and drawReason for a draw" in {
    val json = parse(AppEvent.GameFinished(sid, gid, GameStatus.Draw(DrawReason.Stalemate)))
    json("status").str     shouldBe "Draw"
    json("drawReason").str shouldBe "Stalemate"
  }

  it should "include status=Ongoing and inCheck for an ongoing state" in {
    // Defensive: covers the Ongoing branch even though GameFinished won't carry it in practice
    val json = parse(AppEvent.GameFinished(sid, gid, GameStatus.Ongoing(true)))
    json("status").str   shouldBe "Ongoing"
    json("inCheck").bool shouldBe true
  }

  // ── AITurnRequested ────────────────────────────────────────────────────────

  "AITurnRequested" should "include currentPlayer" in {
    val json = parse(AppEvent.AITurnRequested(sid, gid, Color.Black))
    json("currentPlayer").str shouldBe "Black"
  }

  // ── AITurnCompleted ────────────────────────────────────────────────────────

  "AITurnCompleted" should "include the applied move" in {
    val json = parse(AppEvent.AITurnCompleted(sid, gid, Move(e2, e4)))
    json("move")("from").str shouldBe "e2"
    json("move")("to").str   shouldBe "e4"
  }

  // ── AITurnFailed ──────────────────────────────────────────────────────────

  "AITurnFailed" should "include reason" in {
    val json = parse(AppEvent.AITurnFailed(sid, gid, "no legal moves available"))
    json("reason").str shouldBe "no legal moves available"
  }
