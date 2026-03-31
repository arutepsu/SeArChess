package chess.adapter.textui

import chess.application.{ApplicationError, ChessService, GameState}
import chess.application.ChessCommand.{MakeMove, Promote}
import chess.domain.model.{Move, Position}
import scala.annotation.tailrec

final class TextUI(
    console:      Console,
    initialState: GameState = ChessService.createNewGame()
):

  def run(): Unit =
    console.printLine(ConsoleRenderer.renderWelcome())
    console.printLine(ConsoleRenderer.renderHelp())
    loop(initialState)

  @tailrec
  private def loop(state: GameState): Unit =
    console.printLine("")
    console.printLine(ConsoleRenderer.renderBoard(state))
    console.printLine(ConsoleRenderer.renderStatus(state))
    console.print("> ")
    val input = console.readLine()
    if (input == null) {
      console.printLine("Input was null (EOF). Exiting.")
      return
    }
    InputParser.parse(input) match
      case Left(err) =>
        console.printLine(ConsoleRenderer.renderParseError(err))
        loop(state)

      case Right(TextUiCommand.Quit) =>
        console.printLine("Goodbye!")

      case Right(TextUiCommand.Help) =>
        console.printLine(ConsoleRenderer.renderHelp())
        loop(state)

      case Right(TextUiCommand.Show) =>
        loop(state)

      case Right(TextUiCommand.New) =>
        console.printLine("New game started.")
        loop(ChessService.createNewGame())

      case Right(TextUiCommand.MoveCmd(fromStr, toStr)) =>
        val result = for
          from     <- Position.fromAlgebraic(fromStr).left.map(ApplicationError.DomainFailure(_))
          to       <- Position.fromAlgebraic(toStr).left.map(ApplicationError.DomainFailure(_))
          newState <- ChessService.handleCommand(state, MakeMove(Move(from, to)))
        yield newState
        result match
          case Left(err)       =>
            console.printLine(ConsoleRenderer.renderApplicationError(err))
            loop(state)
          case Right(newState) =>
            if newState.pendingPromotion.isDefined then
              console.printLine(ConsoleRenderer.renderPromotionRequired())
            loop(newState)

      case Right(TextUiCommand.PromoteCmd(pieceType)) =>
        ChessService.handleCommand(state, Promote(pieceType)) match
          case Left(err)       =>
            console.printLine(ConsoleRenderer.renderApplicationError(err))
            loop(state)
          case Right(newState) =>
            loop(newState)
