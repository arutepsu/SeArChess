package chess.adapter.textui

sealed trait InputParseError
object InputParseError:
  case object EmptyInput                               extends InputParseError
  final case class UnknownCommand(input: String)       extends InputParseError
  final case class WrongArgumentCount(command: String) extends InputParseError
