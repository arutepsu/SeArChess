package chess.adapter.gui.viewmodel

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues
import chess.application.ChessService
import chess.domain.model.{Move, PieceType, Position}
import chess.domain.state.GameState

class MoveHistoryViewModelMapperSpec extends AnyFlatSpec with Matchers with OptionValues:

  private def pos(alg: String): Position =
    Position.fromAlgebraic(alg).getOrElse(throw AssertionError(s"Bad alg: $alg"))

  private val freshState = ChessService.createNewGame()

  private def stateWithHistory(moves: Move*): GameState =
    freshState.copy(moveHistory = moves.toList)

  // ── Empty history ────────────────────────────────────────────────────────────

  "MoveHistoryViewModelMapper" should "produce an empty rows vector when there are no moves" in {
    MoveHistoryViewModelMapper.map(freshState).rows shouldBe empty
  }

  // ── Single half-move ─────────────────────────────────────────────────────────

  it should "produce one row with no blackMove when there is exactly one move" in {
    val vm = MoveHistoryViewModelMapper.map(stateWithHistory(Move(pos("e2"), pos("e4"))))
    vm.rows should have size 1
    vm.rows(0).blackMove shouldBe None
  }

  it should "number the single row as 1" in {
    val vm = MoveHistoryViewModelMapper.map(stateWithHistory(Move(pos("e2"), pos("e4"))))
    vm.rows(0).moveNumber shouldBe 1
  }

  it should "format the single move as 'e2-e4'" in {
    val vm = MoveHistoryViewModelMapper.map(stateWithHistory(Move(pos("e2"), pos("e4"))))
    vm.rows(0).whiteMove shouldBe "e2-e4"
  }

  // ── Two half-moves (one full move) ───────────────────────────────────────────

  it should "produce one row with both moves filled when there are exactly two moves" in {
    val vm = MoveHistoryViewModelMapper.map(stateWithHistory(
      Move(pos("e2"), pos("e4")),
      Move(pos("e7"), pos("e5"))
    ))
    vm.rows should have size 1
    vm.rows(0).whiteMove       shouldBe "e2-e4"
    vm.rows(0).blackMove.value shouldBe "e7-e5"
  }

  // ── Three half-moves ─────────────────────────────────────────────────────────

  it should "produce two rows when there are three moves, last row having no blackMove" in {
    val vm = MoveHistoryViewModelMapper.map(stateWithHistory(
      Move(pos("e2"), pos("e4")),
      Move(pos("e7"), pos("e5")),
      Move(pos("g1"), pos("f3"))
    ))
    vm.rows should have size 2
    vm.rows(0).moveNumber      shouldBe 1
    vm.rows(0).whiteMove       shouldBe "e2-e4"
    vm.rows(0).blackMove.value shouldBe "e7-e5"
    vm.rows(1).moveNumber      shouldBe 2
    vm.rows(1).whiteMove       shouldBe "g1-f3"
    vm.rows(1).blackMove       shouldBe None
  }

  // ── Four half-moves ──────────────────────────────────────────────────────────

  it should "produce two complete rows when there are four moves" in {
    val vm = MoveHistoryViewModelMapper.map(stateWithHistory(
      Move(pos("e2"), pos("e4")),
      Move(pos("e7"), pos("e5")),
      Move(pos("g1"), pos("f3")),
      Move(pos("b8"), pos("c6"))
    ))
    vm.rows should have size 2
    vm.rows(1).whiteMove       shouldBe "g1-f3"
    vm.rows(1).blackMove.value shouldBe "b8-c6"
  }

  // ── Promotion formatting ─────────────────────────────────────────────────────

  it should "append '=Q' for a queen promotion" in {
    val vm = MoveHistoryViewModelMapper.map(stateWithHistory(
      Move(pos("e7"), pos("e8"), Some(PieceType.Queen))
    ))
    vm.rows(0).whiteMove shouldBe "e7-e8=Q"
  }

  it should "append '=R' for a rook promotion" in {
    val vm = MoveHistoryViewModelMapper.map(stateWithHistory(
      Move(pos("a7"), pos("a8"), Some(PieceType.Rook))
    ))
    vm.rows(0).whiteMove shouldBe "a7-a8=R"
  }

  it should "append '=B' for a bishop promotion" in {
    val vm = MoveHistoryViewModelMapper.map(stateWithHistory(
      Move(pos("c7"), pos("c8"), Some(PieceType.Bishop))
    ))
    vm.rows(0).whiteMove shouldBe "c7-c8=B"
  }

  it should "append '=N' for a knight promotion" in {
    val vm = MoveHistoryViewModelMapper.map(stateWithHistory(
      Move(pos("d7"), pos("d8"), Some(PieceType.Knight))
    ))
    vm.rows(0).whiteMove shouldBe "d7-d8=N"
  }

  it should "not append a promotion suffix when promotion is None" in {
    val vm = MoveHistoryViewModelMapper.map(stateWithHistory(
      Move(pos("e2"), pos("e4"), None)
    ))
    vm.rows(0).whiteMove shouldBe "e2-e4"
  }

  it should "format a non-promotable piece type as '?'" in {
    // Defensive fallback: King cannot be a promotion target in real chess,
    // but the mapper handles any PieceType gracefully.
    val vm = MoveHistoryViewModelMapper.map(stateWithHistory(
      Move(pos("e7"), pos("e8"), Some(PieceType.King))
    ))
    vm.rows(0).whiteMove shouldBe "e7-e8=?"
  }

  // ── Row numbering ────────────────────────────────────────────────────────────

  it should "number rows consecutively starting at 1 for longer histories" in {
    val moves = (1 to 6).map(_ => Move(pos("e2"), pos("e4"))).toList
    val vm    = MoveHistoryViewModelMapper.map(freshState.copy(moveHistory = moves))
    vm.rows should have size 3
    vm.rows.map(_.moveNumber) shouldBe Vector(1, 2, 3)
  }

  // ── MoveHistoryViewModel.empty ───────────────────────────────────────────────

  "MoveHistoryViewModel.empty" should "have no rows" in {
    MoveHistoryViewModel.empty.rows shouldBe empty
  }
