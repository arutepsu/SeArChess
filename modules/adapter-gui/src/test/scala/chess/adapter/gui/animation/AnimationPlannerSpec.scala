package chess.adapter.gui.animation

import chess.domain.model.{Board, Color, Move, Piece, PieceType, Position}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AnimationPlannerSpec extends AnyFlatSpec with Matchers:

  private def pos(file: Int, rank: Int): Position =
    Position.from(file, rank).getOrElse(throw AssertionError(s"Bad pos: $file,$rank"))

  private val e2 = pos(4, 1)
  private val e4 = pos(4, 3)
  private val d5 = pos(3, 4)

  private val whitePawn  = Piece(Color.White, PieceType.Pawn)
  private val blackPawn  = Piece(Color.Black, PieceType.Pawn)

  // ── Normal move ───────────────────────────────────────────────────────────

  "AnimationPlanner.plan" should "return a plan for a normal move" in {
    val board = Board.empty.place(e2, whitePawn)
    val plan  = AnimationPlanner.plan(board, Move(e2, e4))
    plan shouldBe defined
  }

  it should "record the correct moving piece for a normal move" in {
    val board = Board.empty.place(e2, whitePawn)
    val plan  = AnimationPlanner.plan(board, Move(e2, e4)).get
    plan.movingPiece shouldBe (Color.White, PieceType.Pawn)
  }

  it should "record the correct from and to squares for a normal move" in {
    val board = Board.empty.place(e2, whitePawn)
    val plan  = AnimationPlanner.plan(board, Move(e2, e4)).get
    plan.from shouldBe e2
    plan.to   shouldBe e4
  }

  it should "have no captured piece for a non-capture move" in {
    val board = Board.empty.place(e2, whitePawn)
    val plan  = AnimationPlanner.plan(board, Move(e2, e4)).get
    plan.capturedPiece shouldBe None
    plan.isCapture     shouldBe false
  }

  // ── Capture ───────────────────────────────────────────────────────────────

  it should "return a plan for a capture move" in {
    val board = Board.empty.place(e2, whitePawn).place(d5, blackPawn)
    val plan  = AnimationPlanner.plan(board, Move(e2, d5))
    plan shouldBe defined
  }

  it should "record the captured piece from the previous board snapshot" in {
    val board = Board.empty.place(e2, whitePawn).place(d5, blackPawn)
    val plan  = AnimationPlanner.plan(board, Move(e2, d5)).get
    plan.capturedPiece shouldBe Some((Color.Black, PieceType.Pawn))
    plan.isCapture     shouldBe true
  }

  it should "record the correct moving piece for a capture" in {
    val board = Board.empty.place(e2, whitePawn).place(d5, blackPawn)
    val plan  = AnimationPlanner.plan(board, Move(e2, d5)).get
    plan.movingPiece shouldBe (Color.White, PieceType.Pawn)
  }

  // ── No piece at source ────────────────────────────────────────────────────

  it should "return None when the source square is empty" in {
    val board = Board.empty
    AnimationPlanner.plan(board, Move(e2, e4)) shouldBe None
  }

  // ── Castling exclusion ────────────────────────────────────────────────────

  it should "return None for a king moving two squares (castling)" in {
    val e1 = pos(4, 0)
    val g1 = pos(6, 0)
    val king  = Piece(Color.White, PieceType.King)
    val board = Board.empty.place(e1, king)
    AnimationPlanner.plan(board, Move(e1, g1)) shouldBe None
  }

  it should "return None for queen-side castling (king moves 2 squares left)" in {
    val e1 = pos(4, 0)
    val c1 = pos(2, 0)
    val king  = Piece(Color.White, PieceType.King)
    val board = Board.empty.place(e1, king)
    AnimationPlanner.plan(board, Move(e1, c1)) shouldBe None
  }

  it should "return a plan for a king moving one square (not castling)" in {
    val e1 = pos(4, 0)
    val f1 = pos(5, 0)
    val king  = Piece(Color.White, PieceType.King)
    val board = Board.empty.place(e1, king)
    val plan  = AnimationPlanner.plan(board, Move(e1, f1))
    plan shouldBe defined
    plan.get.movingPiece shouldBe (Color.White, PieceType.King)
  }

  // ── Default duration ──────────────────────────────────────────────────────

  it should "use the default animation duration for a normal move" in {
    val board = Board.empty.place(e2, whitePawn)
    val plan  = AnimationPlanner.plan(board, Move(e2, e4)).get
    plan.durationMs shouldBe 340
  }

  it should "use captureTimings.totalMs as durationMs for a capture move" in {
    val board = Board.empty.place(e2, whitePawn).place(d5, blackPawn)
    val plan  = AnimationPlanner.plan(board, Move(e2, d5)).get
    plan.durationMs shouldBe plan.captureTimings.totalMs
  }

  it should "use a longer duration for a capture than for a normal move" in {
    val normalPlan  = AnimationPlanner.plan(Board.empty.place(e2, whitePawn), Move(e2, e4)).get
    val capturePlan = AnimationPlanner.plan(Board.empty.place(e2, whitePawn).place(d5, blackPawn), Move(e2, d5)).get
    capturePlan.durationMs should be > normalPlan.durationMs
  }
