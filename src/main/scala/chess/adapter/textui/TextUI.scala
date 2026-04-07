package chess.adapter.textui

import chess.application.{ApplicationError, ChessService, ObservableGame}
import chess.domain.error.DomainError
import chess.domain.state.GameState
import chess.application.ChessCommand.MakeMove
import chess.domain.model.{Move, Position}
import scala.annotation.tailrec

final class TextUI(
    console:  Console,
    game:     ObservableGame
):

  def run(): Unit =
    console.printLine(ConsoleRenderer.renderWelcome())
    console.printLine(ConsoleRenderer.renderHelp())
    
    // Register to automatically print the board when state updates
    game.addObserver { state =>
      console.printLine("")
      console.printLine(ConsoleRenderer.renderBoard(state))
      console.printLine(ConsoleRenderer.renderStatus(state))
      console.print("> ")
    }
    
    // Print initial state manually since we are just starting
    val initialState = game.getState
    console.printLine("")
    console.printLine(ConsoleRenderer.renderBoard(initialState))
    console.printLine(ConsoleRenderer.renderStatus(initialState))
    console.print("> ")
    
    loop()

  @tailrec
  private def loop(): Unit =
    val input = console.readLine()
    val state = game.getState

    if input == null then { console.printLine("Goodbye!"); return }
    InputParser.parse(input) match
      case Left(err) =>
        console.printLine(ConsoleRenderer.renderParseError(err))
        console.print("> ")
        loop()

      case Right(TextUiCommand.Quit) =>
        console.printLine("Goodbye!")

      case Right(TextUiCommand.Help) =>
        console.printLine(ConsoleRenderer.renderHelp())
        console.print("> ")
        loop()

      case Right(TextUiCommand.Show) =>
        console.printLine("")
        console.printLine(ConsoleRenderer.renderBoard(state))
        console.printLine(ConsoleRenderer.renderStatus(state))
        console.print("> ")
        loop()

      case Right(TextUiCommand.New) =>
        console.printLine("New game started.")
        game.updateState(ChessService.createNewGame())
        loop()

      case Right(TextUiCommand.MoveCmd(fromStr, toStr)) =>
        val result = for
          from     <- Position.fromAlgebraic(fromStr).left.map(ApplicationError.DomainFailure(_))
          to       <- Position.fromAlgebraic(toStr).left.map(ApplicationError.DomainFailure(_))
          newState <- ChessService.handleCommand(state, MakeMove(Move(from, to)))
        yield newState

        result match
          case Left(err) =>
            console.printLine(ConsoleRenderer.renderApplicationError(err))
            console.print("> ")
          case Right(newState) =>
            game.updateState(newState)
        loop()
