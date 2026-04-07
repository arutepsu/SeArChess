package chess.adapter.textui

import chess.application.{ApplicationError, ChessService}
import chess.domain.error.DomainError
import chess.domain.state.GameState
import chess.application.ChessCommand.MakeMove
import chess.domain.model.{Move, Position}
import scala.annotation.tailrec

final class TextUI(
    console:      Console,
    initialState: GameState = ChessService.createNewGame()
):

  def run(): Unit =
    console.printLine(ConsoleRenderer.renderWelcome())
    console.printLine(ConsoleRenderer.renderHelp())
    loop(initialState, None)

  /** @param pendingMove  a pawn move that requires a promotion piece choice;
   *                      held between the `move` command and the `promote` command.
   */
  @tailrec
  private def loop(state: GameState, pendingMove: Option[Move]): Unit =
    console.printLine("")
    console.printLine(ConsoleRenderer.renderBoard(state))
    console.printLine(ConsoleRenderer.renderStatus(state))
    console.print("> ")
    val input = console.readLine()
    if input == null then { console.printLine("Goodbye!"); return }
    InputParser.parse(input) match
      case Left(err) =>
        console.printLine(ConsoleRenderer.renderParseError(err))
        loop(state, pendingMove)

      case Right(TextUiCommand.Quit) =>
        console.printLine("Goodbye!")

      case Right(TextUiCommand.Help) =>
        console.printLine(ConsoleRenderer.renderHelp())
        loop(state, pendingMove)

      case Right(TextUiCommand.Show) =>
        loop(state, pendingMove)

      case Right(TextUiCommand.New) =>
        console.printLine("New game started.")
        loop(ChessService.createNewGame(), None)

      case Right(TextUiCommand.MoveCmd(fromStr, toStr)) =>
        val posResult = for
          from <- Position.fromAlgebraic(fromStr)
          to   <- Position.fromAlgebraic(toStr)
        yield (from, to)
        posResult match
          case Left(err) =>
            console.printLine(ConsoleRenderer.renderApplicationError(ApplicationError.DomainFailure(err)))
            loop(state, pendingMove)
          case Right((from, to)) =>
            ChessService.handleCommand(state, MakeMove(Move(from, to))) match
              case Left(ApplicationError.DomainFailure(DomainError.MissingPromotionChoice)) =>
                console.printLine(ConsoleRenderer.renderPromotionRequired())
                loop(state, Some(Move(from, to)))
              case Left(err) =>
                console.printLine(ConsoleRenderer.renderApplicationError(err))
                loop(state, pendingMove)
              case Right(newState) =>
                loop(newState, None)

      case Right(TextUiCommand.PromoteCmd(pieceType)) =>
        pendingMove match
          case None =>
            console.printLine("No pawn promotion is pending.")
            loop(state, None)
          case Some(pm) =>
            ChessService.handleCommand(state, MakeMove(Move(pm.from, pm.to, Some(pieceType)))) match
              // $COVERAGE-OFF$ promotion with q/r/b/n on the same board that already passed
              // king-safety for the move command cannot fail; guard kept for exhaustiveness
              case Left(err) =>
                console.printLine(ConsoleRenderer.renderApplicationError(err))
                loop(state, Some(pm))
              // $COVERAGE-ON$
              case Right(newState) =>
                loop(newState, None)
