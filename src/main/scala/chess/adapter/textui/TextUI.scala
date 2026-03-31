package chess.adapter.textui

import chess.application.{ApplicationError, ChessService, GameState}
import chess.application.ChessCommand.{MakeMove, Promote}
import chess.domain.model.{Move, Position}
import scala.annotation.tailrec

final class TextUI(

      // Closure-Beispiel: Zählt, wie oft ein Kommando verarbeitet wurde
      private def makeCommandCounter(): TextUiCommand => Unit =
        var count = 0
        (cmd: TextUiCommand) =>
          count += 1
          console.printLine(s"[Command count: $count] $cmd")

      // Closure-Beispiel: Funktion, die ein Logging-Präfix aus dem Scope verwendet
      private def makeLogger(prefix: String): String => Unit =
        (msg: String) => console.printLine(s"$prefix$msg")

      // Beispiel für Nutzung der Closures
      private val commandCounter = makeCommandCounter()
      private val infoLogger    = makeLogger("[INFO] ")

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
      infoLogger("Input was null (EOF). Exiting.")
      return
    }
    InputParser.parse(input) match
      case Left(err) =>
        infoLogger(ConsoleRenderer.renderParseError(err))
        loop(state)

      case Right(cmd) =>
        commandCounter(cmd)
        cmd match
          case TextUiCommand.Quit =>
            infoLogger("Goodbye!")
          case TextUiCommand.Help =>
            infoLogger(ConsoleRenderer.renderHelp())
            loop(state)
          case _ =>
            // ...bestehende Logik für andere Kommandos...
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
