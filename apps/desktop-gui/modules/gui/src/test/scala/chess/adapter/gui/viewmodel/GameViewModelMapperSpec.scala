package chess.adapter.gui.viewmodel

import chess.application.GameStateCommandService
import chess.domain.model.{Board, Color, DrawReason, GameStatus, Piece, PieceType, Position}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GameViewModelMapperSpec extends AnyFlatSpec with Matchers:

  private def algPos(alg: String): Position =
    Position.fromAlgebraic(alg).getOrElse(throw AssertionError(s"Bad algebraic: $alg"))

  private val freshState = GameStateCommandService.createNewGame()

  // ── build ─────────────────────────────────────────────────────────────────

  "GameViewModelMapper.build" should "produce 64 squares" in {
    GameViewModelMapper.build(freshState, GuiState.WaitingForSelection).squares should have size 64
  }

  it should "set the correct guiState" in {
    val vm = GameViewModelMapper.build(freshState, GuiState.Animating)
    vm.guiState shouldBe GuiState.Animating
  }

  it should "set promotion to None by default" in {
    GameViewModelMapper.build(freshState, GuiState.WaitingForSelection).promotion shouldBe None
  }

  it should "include the provided promotion view model" in {
    val from = algPos("a7")
    val to = algPos("a8")
    val promoVm = PromotionViewModel(Color.White, PromotionViewModel.standardChoices)
    val vm =
      GameViewModelMapper.build(freshState, GuiState.AwaitingPromotion(from, to), Some(promoVm))
    vm.promotion shouldBe Some(promoVm)
  }

  // ── buildSquares ──────────────────────────────────────────────────────────

  "GameViewModelMapper.buildSquares" should "produce 64 SquareViewModels" in {
    GameViewModelMapper.buildSquares(freshState, GuiState.WaitingForSelection) should have size 64
  }

  it should "mark the selected square isSelected=true in PieceSelected state" in {
    val e2 = algPos("e2")
    val guiSt = GuiState.PieceSelected(e2, Set.empty)
    val sqs = GameViewModelMapper.buildSquares(freshState, guiSt)
    sqs.filter(_.isSelected).map(_.position) shouldBe Seq(e2)
  }

  it should "mark legal targets isLegalTarget=true in PieceSelected state" in {
    val e2 = algPos("e2")
    val e3 = algPos("e3")
    val e4 = algPos("e4")
    val sqs = GameViewModelMapper.buildSquares(freshState, GuiState.PieceSelected(e2, Set(e3, e4)))
    sqs.filter(_.isLegalTarget).map(_.position).toSet shouldBe Set(e3, e4)
  }

  it should "not select any squares in WaitingForSelection state" in {
    val sqs = GameViewModelMapper.buildSquares(freshState, GuiState.WaitingForSelection)
    sqs.filter(_.isSelected) shouldBe empty
    sqs.filter(_.isLegalTarget) shouldBe empty
  }

  it should "not select any squares in Animating state" in {
    val sqs = GameViewModelMapper.buildSquares(freshState, GuiState.Animating)
    sqs.filter(_.isSelected) shouldBe empty
    sqs.filter(_.isLegalTarget) shouldBe empty
  }

  // ── renderStatus ──────────────────────────────────────────────────────────

  "GameViewModelMapper.renderStatus" should "say White to move at game start" in {
    GameViewModelMapper.renderStatus(freshState) shouldBe "White to move"
  }

  it should "say Black to move when it is Black's turn" in {
    val state = freshState.copy(currentPlayer = Color.Black)
    GameViewModelMapper.renderStatus(state) shouldBe "Black to move"
  }

  it should "include CHECK when the current player is in check" in {
    val state = freshState.copy(status = GameStatus.Ongoing(true))
    GameViewModelMapper.renderStatus(state) should include("CHECK")
  }

  it should "indicate Checkmate with winner name — Black wins when White is mated" in {
    val whiteToMove =
      freshState.copy(currentPlayer = Color.White, status = GameStatus.Checkmate(Color.Black))
    GameViewModelMapper.renderStatus(whiteToMove) should (include("Checkmate") and include(
      "Black wins"
    ))
  }

  it should "indicate Checkmate with winner name — White wins when Black is mated" in {
    val blackToMove =
      freshState.copy(currentPlayer = Color.Black, status = GameStatus.Checkmate(Color.White))
    GameViewModelMapper.renderStatus(blackToMove) should (include("Checkmate") and include(
      "White wins"
    ))
  }

  it should "indicate Stalemate as a draw" in {
    GameViewModelMapper.renderStatus(
      freshState.copy(status = GameStatus.Draw(DrawReason.Stalemate))
    ) should include("draw")
  }
