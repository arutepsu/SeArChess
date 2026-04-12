package chess.adapter.textui

import chess.application.{ApplicationError, ChessService, ObservableGame}
import chess.domain.error.DomainError
import chess.domain.state.GameState
import chess.application.ChessCommand.MakeMove
import chess.domain.model.{Move, Position}
import scala.annotation.tailrec

final class TextUI(
    console: Console,
    game:    ObservableGame
):

  def run(): TuiExitReason =
    console.printLine(ConsoleRenderer.renderWelcome())
    console.printLine(ConsoleRenderer.renderHelp())

    // Print board on every external state change (e.g. GUI makes a move).
    // Called on whatever thread invoked updateState; stdout writes are
    // individually thread-safe so there is no data corruption, though
    // output may visually interleave with the TUI prompt.
    game.addObserver { state =>
      console.printLine("")
      console.printLine(ConsoleRenderer.renderBoard(state))
      console.printLine(ConsoleRenderer.renderStatus(state))
      console.print("> ")
    }

    val initial = game.getState
    console.printLine("")
    console.printLine(ConsoleRenderer.renderBoard(initial))
    console.printLine(ConsoleRenderer.renderStatus(initial))
    console.print("> ")

    loop(pendingMove = None)

  @tailrec
  private def loop(pendingMove: Option[Move]): TuiExitReason =
    val input = console.readLine()
    val state = game.getState

    if input == null then
      console.printLine("Goodbye!")
      return TuiExitReason.EndOfInput

    InputParser.parse(input) match
      case Left(err) =>
        console.printLine(ConsoleRenderer.renderParseError(err))
        console.print("> ")
        loop(pendingMove)

      case Right(TextUiCommand.Quit) =>
        console.printLine("Goodbye!")
        TuiExitReason.UserQuit

      case Right(TextUiCommand.Help) =>
        console.printLine(ConsoleRenderer.renderHelp())
        console.print("> ")
        loop(pendingMove)

      case Right(TextUiCommand.Show) =>
        console.printLine("")
        console.printLine(ConsoleRenderer.renderBoard(state))
        console.printLine(ConsoleRenderer.renderStatus(state))
        console.print("> ")
        loop(pendingMove)

      case Right(TextUiCommand.New) =>
        console.printLine("New game started.")
        game.updateState(ChessService.createNewGame())
        loop(pendingMove = None)

      case Right(TextUiCommand.MoveCmd(fromStr, toStr)) =>
        val posResult = for
          from <- Position.fromAlgebraic(fromStr).left.map(ApplicationError.DomainFailure(_))
          to   <- Position.fromAlgebraic(toStr).left.map(ApplicationError.DomainFailure(_))
        yield (from, to)

        posResult match
          case Left(err) =>
            console.printLine(ConsoleRenderer.renderApplicationError(err))
            console.print("> ")
            loop(pendingMove)
          case Right((from, to)) =>
            ChessService.handleCommand(state, MakeMove(Move(from, to))) match
              case Left(ApplicationError.DomainFailure(DomainError.MissingPromotionChoice)) =>
                console.printLine(ConsoleRenderer.renderPromotionRequired())
                loop(pendingMove = Some(Move(from, to)))
              case Left(err) =>
                console.printLine(ConsoleRenderer.renderApplicationError(err))
                console.print("> ")
                loop(pendingMove)
              case Right(newState) =>
                game.updateState(newState)
                loop(pendingMove = None)

      case Right(TextUiCommand.PromoteCmd(pieceType)) =>
        pendingMove match
          case None =>
            console.printLine("No pawn promotion pending.")
            console.print("> ")
            loop(pendingMove = None)
          case Some(pm) =>
            // $COVERAGE-OFF$ promotion with q/r/b/n on a valid board cannot fail
            ChessService.handleCommand(state, MakeMove(Move(pm.from, pm.to, Some(pieceType)))) match
              case Left(err) =>
                console.printLine(ConsoleRenderer.renderApplicationError(err))
                console.print("> ")
                loop(pendingMove = Some(pm))
              // $COVERAGE-ON$
              case Right(newState) =>
                game.updateState(newState)
                loop(pendingMove = None)

object TextUI:
  /** Convenience constructor using a fresh game. */
  def apply(console: Console): TextUI =
    new TextUI(console, new ObservableGame())

  /** Convenience constructor from an initial [[GameState]]. */
  def apply(console: Console, initialState: GameState): TextUI =
    new TextUI(console, new ObservableGame(initialState))