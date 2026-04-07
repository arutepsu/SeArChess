package chess.adapter.gui.viewmodel

import chess.application.ChessService
import chess.domain.model.{Move, PieceType, Position}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MoveHistoryViewModelMapperSpec extends AnyFlatSpec with Matchers:

  private def pos(alg: String): Position =
    Position.fromAlgebraic(alg).getOrElse(throw AssertionError(s"Invalid position: $alg"))

  private def mv(from: String, to: String): Move =
    Move(pos(from), pos(to))

  private def mvPromo(from: String, to: String, pt: PieceType): Move =
    Move(pos(from), pos(to), Some(pt))

  // ── Empty history ────────────────────────────────────────────────────────────

  "MoveHistoryViewModelMapper.from(List[Move])" should "return an empty view model for empty history" in {
    val vm = MoveHistoryViewModelMapper.from(List.empty)
    vm.isEmpty      shouldBe true
    vm.rows         shouldBe empty
  }

  it should "return latestRowIndex=None for empty history" in {
    MoveHistoryViewModelMapper.from(List.empty).latestRowIndex shouldBe None
  }

  // ── Single white ply ─────────────────────────────────────────────────────────

  it should "produce one row with no black move for a single white ply" in {
    val vm = MoveHistoryViewModelMapper.from(List(mv("e2", "e4")))
    vm.rows             should have size 1
    vm.rows(0).moveNumber shouldBe 1
    vm.rows(0).whiteMove  shouldBe "e2-e4"
    vm.rows(0).blackMove  shouldBe None
  }

  // ── Two plies (one full row) ──────────────────────────────────────────────────

  it should "produce one full row for two plies" in {
    val vm = MoveHistoryViewModelMapper.from(List(mv("e2", "e4"), mv("e7", "e5")))
    vm.rows             should have size 1
    vm.rows(0).moveNumber shouldBe 1
    vm.rows(0).whiteMove  shouldBe "e2-e4"
    vm.rows(0).blackMove  shouldBe Some("e7-e5")
  }

  // ── Multiple rows ────────────────────────────────────────────────────────────

  it should "produce two rows for four plies with correct move numbers" in {
    val history = List(
      mv("e2", "e4"), mv("e7", "e5"),
      mv("g1", "f3"), mv("b8", "c6")
    )
    val vm = MoveHistoryViewModelMapper.from(history)
    vm.rows should have size 2
    vm.rows(0).moveNumber shouldBe 1
    vm.rows(0).whiteMove  shouldBe "e2-e4"
    vm.rows(0).blackMove  shouldBe Some("e7-e5")
    vm.rows(1).moveNumber shouldBe 2
    vm.rows(1).whiteMove  shouldBe "g1-f3"
    vm.rows(1).blackMove  shouldBe Some("b8-c6")
  }

  it should "produce correct move numbers for six plies" in {
    val history = List(
      mv("e2", "e4"), mv("e7", "e5"),
      mv("d2", "d4"), mv("d7", "d5"),
      mv("c2", "c4"), mv("c7", "c5")
    )
    val vm = MoveHistoryViewModelMapper.from(history)
    vm.rows.map(_.moveNumber) shouldBe Vector(1, 2, 3)
  }

  // ── Odd ply count ─────────────────────────────────────────────────────────────

  it should "handle an odd number of plies: last row has no black move" in {
    val history = List(
      mv("e2", "e4"), mv("e7", "e5"),
      mv("g1", "f3")
    )
    val vm = MoveHistoryViewModelMapper.from(history)
    vm.rows should have size 2
    vm.rows(1).moveNumber shouldBe 2
    vm.rows(1).whiteMove  shouldBe "g1-f3"
    vm.rows(1).blackMove  shouldBe None
  }

  // ── Move text formatting ──────────────────────────────────────────────────────

  it should "format a normal move as 'from-to'" in {
    val vm = MoveHistoryViewModelMapper.from(List(mv("d2", "d4")))
    vm.rows(0).whiteMove shouldBe "d2-d4"
  }

  it should "format a promotion move as 'from-to=X'" in {
    val vm = MoveHistoryViewModelMapper.from(List(mvPromo("a7", "a8", PieceType.Queen)))
    vm.rows(0).whiteMove shouldBe "a7-a8=Q"
  }

  it should "format all four promotion piece types correctly" in {
    val cases = List(
      (PieceType.Queen,  "a7-a8=Q"),
      (PieceType.Rook,   "a7-a8=R"),
      (PieceType.Bishop, "a7-a8=B"),
      (PieceType.Knight, "a7-a8=N")
    )
    cases.foreach { case (pt, expected) =>
      val vm = MoveHistoryViewModelMapper.from(List(mvPromo("a7", "a8", pt)))
      vm.rows(0).whiteMove shouldBe expected
    }
  }

  it should "format black's promotion move in the blackMove field" in {
    val vm = MoveHistoryViewModelMapper.from(List(
      mv("e2", "e4"),
      mvPromo("a2", "a1", PieceType.Rook)
    ))
    vm.rows(0).blackMove shouldBe Some("a2-a1=R")
  }

  // ── latestRowIndex ────────────────────────────────────────────────────────────

  it should "set latestRowIndex to Some(0) for a single-ply history" in {
    val vm = MoveHistoryViewModelMapper.from(List(mv("e2", "e4")))
    vm.latestRowIndex shouldBe Some(0)
  }

  it should "set latestRowIndex to Some(rows.size - 1) for multi-row history" in {
    val history = List(
      mv("e2", "e4"), mv("e7", "e5"),
      mv("g1", "f3")
    )
    val vm = MoveHistoryViewModelMapper.from(history)
    vm.latestRowIndex shouldBe Some(1)
  }

  // ── GameState delegation ──────────────────────────────────────────────────────

  "MoveHistoryViewModelMapper.from(GameState)" should "return an empty view model for a fresh game" in {
    val state = ChessService.createNewGame()
    val vm    = MoveHistoryViewModelMapper.from(state)
    vm.isEmpty shouldBe true
  }
