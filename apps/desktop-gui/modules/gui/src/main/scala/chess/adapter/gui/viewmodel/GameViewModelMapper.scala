package chess.adapter.gui.viewmodel

import chess.domain.state.GameState
import chess.domain.model.{Color, DrawReason, GameStatus, Position}

/** Pure mapper: builds a [[GameViewModel]] from application state and GUI state.
 *
 *  Extracted from [[chess.adapter.gui.controller.GameController]] so the controller
 *  handles only orchestration, not ViewModel construction.
 */
object GameViewModelMapper:

  def build(
      state:     GameState,
      guiState:  GuiState,
      promotion: Option[PromotionViewModel] = None
  ): GameViewModel =
    GameViewModel(
      squares    = buildSquares(state, guiState),
      guiState   = guiState,
      statusText = renderStatus(state),
      promotion  = promotion
    )

  def buildSquares(state: GameState, guiState: GuiState): IndexedSeq[SquareViewModel] =
    val selected = guiState match
      case GuiState.PieceSelected(pos, _) => Some(pos)
      case _                              => None
    val targets = guiState match
      case GuiState.PieceSelected(_, t) => t
      case _                            => Set.empty[Position]

    (for
      r   <- 0 to 7
      f   <- 0 to 7
      pos <- Position.from(f, r).toOption.toSeq
    yield SquareViewModel(
      position      = pos,
      piece         = state.board.pieceAt(pos).map(p => (p.color, p.pieceType)),
      isSelected    = selected.contains(pos),
      isLegalTarget = targets.contains(pos)
    )).toIndexedSeq

  def renderStatus(state: GameState): String =
    val player = if state.currentPlayer == Color.White then "White" else "Black"
    state.status match
      case GameStatus.Ongoing(false)    => s"$player to move"
      case GameStatus.Ongoing(true)     => s"$player is in CHECK!"
      case GameStatus.Checkmate(winner) =>
        val winnerName = if winner == Color.White then "White" else "Black"
        s"Checkmate — $winnerName wins!"
      case GameStatus.Draw(DrawReason.Stalemate) => "Stalemate — it's a draw!"
      case GameStatus.Resigned(winner) =>
        val winnerName = if winner == Color.White then "White" else "Black"
        s"$winnerName wins by resignation!"
