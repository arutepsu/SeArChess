package chess.adapter.textui

trait Console:
  def readLine(): String
  def print(text: String): Unit
  def printLine(text: String): Unit

object ConsoleIO extends Console:
  private[textui] var in: () => String = () => scala.io.StdIn.readLine()

  def readLine(): String = in()
  def print(text: String): Unit = scala.Console.print(text)
  def printLine(text: String): Unit = scala.Console.println(text)
