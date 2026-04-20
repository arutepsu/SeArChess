package chess.adapter.event

import chess.application.event.AppEvent
import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, DrawReason, GameStatus, Move, PieceType, Position}
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests for [[AppEventSerializer]].
 *
 *  Covers: wire type names (stability), required field presence, payload
 *  correctness for all five boundary events, promotion encoding, controller
 *  string encoding, non-boundary event passthrough, and terminal result
 *  encoding for Checkmate and Draw.
 */
class AppEventSerializerSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  // ── Test fixtures ─────────────────────────────────────────────────────────────

  private val sid = SessionId.random()
  private val gid = GameId.random()

  private def parse(json: String): ujson.Obj =
    ujson.read(json).obj

  private def pos(algebraic: String): Position =
    Position.fromAlgebraic(algebraic).value

  // ── SessionCreated ────────────────────────────────────────────────────────────

  "AppEventSerializer.serialize" should "use the stable type name 'game.session.created.v1'" in {
    val event = AppEvent.SessionCreated(sid, gid, SessionMode.HumanVsHuman,
                  SideController.HumanLocal, SideController.HumanLocal)
    val json  = AppEventSerializer.serialize(event).value
    parse(json)("type").str shouldBe "game.session.created.v1"
  }

  it should "include sessionId and gameId as UUID strings in SessionCreated" in {
    val event = AppEvent.SessionCreated(sid, gid, SessionMode.HumanVsHuman,
                  SideController.HumanLocal, SideController.HumanLocal)
    val obj   = parse(AppEventSerializer.serialize(event).value)
    obj("sessionId").str shouldBe sid.value.toString
    obj("gameId").str    shouldBe gid.value.toString
  }

  it should "encode mode and controller fields in SessionCreated" in {
    val event = AppEvent.SessionCreated(sid, gid, SessionMode.HumanVsAI,
                  SideController.HumanLocal, SideController.AI())
    val obj   = parse(AppEventSerializer.serialize(event).value)
    obj("mode").str            shouldBe "HumanVsAI"
    obj("whiteController").str shouldBe "HumanLocal"
    obj("blackController").str shouldBe "AI"
  }

  it should "encode AI controller with engine id as 'AI:<engineId>'" in {
    val event = AppEvent.SessionCreated(sid, gid, SessionMode.AIVsAI,
                  SideController.AI(Some("stockfish-15")), SideController.AI(Some("random")))
    val obj   = parse(AppEventSerializer.serialize(event).value)
    obj("whiteController").str shouldBe "AI:stockfish-15"
    obj("blackController").str shouldBe "AI:random"
  }

  // ── MoveApplied ───────────────────────────────────────────────────────────────

  it should "use the stable type name 'game.move.applied.v1'" in {
    val event = AppEvent.MoveApplied(sid, gid, Move(pos("e2"), pos("e4")), Color.White)
    val json  = AppEventSerializer.serialize(event).value
    parse(json)("type").str shouldBe "game.move.applied.v1"
  }

  it should "encode move fields as algebraic squares in MoveApplied" in {
    val event = AppEvent.MoveApplied(sid, gid, Move(pos("d7"), pos("d5")), Color.Black)
    val obj   = parse(AppEventSerializer.serialize(event).value)
    obj("move")("from").str   shouldBe "d7"
    obj("move")("to").str     shouldBe "d5"
    obj("move")("promotion")  shouldBe ujson.Null
    obj("playerWhoMoved").str shouldBe "Black"
  }

  it should "encode promotion piece type when move promotes a pawn" in {
    val event = AppEvent.MoveApplied(sid, gid,
                  Move(pos("e7"), pos("e8"), Some(PieceType.Queen)), Color.White)
    val obj   = parse(AppEventSerializer.serialize(event).value)
    obj("move")("promotion").str shouldBe "Queen"
  }

  it should "encode promotion as null when no promotion" in {
    val event = AppEvent.MoveApplied(sid, gid, Move(pos("e2"), pos("e4")), Color.White)
    val obj   = parse(AppEventSerializer.serialize(event).value)
    obj("move")("promotion") shouldBe ujson.Null
  }

  it should "encode all promotion piece types correctly" in {
    val promotions = Map(
      PieceType.Queen  -> "Queen",
      PieceType.Rook   -> "Rook",
      PieceType.Bishop -> "Bishop",
      PieceType.Knight -> "Knight"
    )
    promotions.foreach { case (pt, expected) =>
      val event = AppEvent.MoveApplied(sid, gid, Move(pos("a7"), pos("a8"), Some(pt)), Color.White)
      val obj   = parse(AppEventSerializer.serialize(event).value)
      obj("move")("promotion").str shouldBe expected
    }
  }

  // ── GameFinished — Checkmate ───────────────────────────────────────────────────

  it should "use the stable type name 'game.finished.v1'" in {
    val event = AppEvent.GameFinished(sid, gid, GameStatus.Checkmate(Color.White))
    val json  = AppEventSerializer.serialize(event).value
    parse(json)("type").str shouldBe "game.finished.v1"
  }

  it should "encode Checkmate(White) as result=Checkmate, winner=White, drawReason=null" in {
    val event = AppEvent.GameFinished(sid, gid, GameStatus.Checkmate(Color.White))
    val obj   = parse(AppEventSerializer.serialize(event).value)
    obj("result").str   shouldBe "Checkmate"
    obj("winner").str   shouldBe "White"
    obj("drawReason")   shouldBe ujson.Null
  }

  it should "encode Checkmate(Black) as result=Checkmate, winner=Black" in {
    val event = AppEvent.GameFinished(sid, gid, GameStatus.Checkmate(Color.Black))
    val obj   = parse(AppEventSerializer.serialize(event).value)
    obj("result").str shouldBe "Checkmate"
    obj("winner").str shouldBe "Black"
  }

  it should "encode Draw(Stalemate) as result=Draw, winner=null, drawReason=Stalemate" in {
    val event = AppEvent.GameFinished(sid, gid, GameStatus.Draw(DrawReason.Stalemate))
    val obj   = parse(AppEventSerializer.serialize(event).value)
    obj("result").str     shouldBe "Draw"
    obj("winner")         shouldBe ujson.Null
    obj("drawReason").str shouldBe "Stalemate"
  }

  // ── GameResigned ──────────────────────────────────────────────────────────────

  it should "use the stable type name 'game.resigned.v1'" in {
    val event = AppEvent.GameResigned(sid, gid, Color.Black)
    val json  = AppEventSerializer.serialize(event).value
    parse(json)("type").str shouldBe "game.resigned.v1"
  }

  it should "encode winner=Black when White resigns (Black is the winner)" in {
    val event = AppEvent.GameResigned(sid, gid, Color.Black)
    val obj   = parse(AppEventSerializer.serialize(event).value)
    obj("winner").str shouldBe "Black"
  }

  it should "encode winner=White when Black resigns" in {
    val event = AppEvent.GameResigned(sid, gid, Color.White)
    val obj   = parse(AppEventSerializer.serialize(event).value)
    obj("winner").str shouldBe "White"
  }

  it should "include sessionId and gameId in GameResigned" in {
    val event = AppEvent.GameResigned(sid, gid, Color.White)
    val obj   = parse(AppEventSerializer.serialize(event).value)
    obj("sessionId").str shouldBe sid.value.toString
    obj("gameId").str    shouldBe gid.value.toString
  }

  // ── SessionCancelled ──────────────────────────────────────────────────────────

  it should "use the stable type name 'game.session.cancelled.v1'" in {
    val event = AppEvent.SessionCancelled(sid, gid)
    val json  = AppEventSerializer.serialize(event).value
    parse(json)("type").str shouldBe "game.session.cancelled.v1"
  }

  it should "include only type, sessionId, and gameId in SessionCancelled" in {
    val event = AppEvent.SessionCancelled(sid, gid)
    val obj   = parse(AppEventSerializer.serialize(event).value)
    obj.value.keySet shouldBe Set("type", "sessionId", "gameId")
  }

  // ── Non-boundary events return None ──────────────────────────────────────────

  it should "return None for MoveRejected (internal-only event)" in {
    val move  = Move(pos("e2"), pos("e4"))
    val event = AppEvent.MoveRejected(sid, gid, move, "illegal move")
    AppEventSerializer.serialize(event) shouldBe None
  }

  it should "return None for PromotionPending (internal-only event)" in {
    val event = AppEvent.PromotionPending(sid, gid)
    AppEventSerializer.serialize(event) shouldBe None
  }

  it should "return None for SessionLifecycleChanged (internal-only event)" in {
    import chess.application.session.model.SessionLifecycle
    val event = AppEvent.SessionLifecycleChanged(sid, gid,
                  SessionLifecycle.Active, SessionLifecycle.Finished)
    AppEventSerializer.serialize(event) shouldBe None
  }

  it should "return None for AITurnRequested (internal monitoring event)" in {
    val event = AppEvent.AITurnRequested(sid, gid, Color.White)
    AppEventSerializer.serialize(event) shouldBe None
  }

  // ── All five wire events return Some ─────────────────────────────────────────

  it should "return Some for all five wire-contract events" in {
    val events: List[AppEvent] = List(
      AppEvent.SessionCreated(sid, gid, SessionMode.HumanVsHuman,
        SideController.HumanLocal, SideController.HumanLocal),
      AppEvent.MoveApplied(sid, gid, Move(pos("e2"), pos("e4")), Color.White),
      AppEvent.GameFinished(sid, gid, GameStatus.Checkmate(Color.White)),
      AppEvent.GameResigned(sid, gid, Color.Black),
      AppEvent.SessionCancelled(sid, gid)
    )
    events.foreach { event =>
      AppEventSerializer.serialize(event) shouldBe defined
    }
  }
