package chess.workbench

import chess.workbench.review.ReviewCommand

object WorkbenchCli:
  type Fallback = (List[String], String => Unit, String => Unit) => Int

  def main(args: Array[String]): Unit =
    val code = dispatch(
      args = args.toList,
      printLine = println,
      printError = Console.err.println
    )
    if code != 0 then sys.exit(code)

  def dispatch(
      args: List[String],
      printLine: String => Unit,
      printError: String => Unit,
      fallback: Fallback = defaultFallback
  ): Int =
    args match
      case "review" :: _ =>
        ReviewCommand.execute(args, printLine)
        0
      case Nil | "help" :: Nil | "--help" :: Nil | "-h" :: Nil =>
        HelpLines.foreach(printLine)
        0
      case _ =>
        fallback(args, printLine, printError)

  private val HelpLines: Vector[String] = Vector(
    "Usage: workbench <command> [options]",
    "",
    "Commands:",
    "  review        Run the stub AI review for the project",
    "  review game   Run the stub AI review for the game module",
    "  review tests  Run the stub AI review for tests",
    "  help          Show this help"
  )

  private def defaultFallback(
      args: List[String],
      printLine: String => Unit,
      printError: String => Unit
  ): Int =
    val command = args.headOption.getOrElse("")
    printError(s"Unknown workbench command: $command")
    HelpLines.foreach(printLine)
    1
