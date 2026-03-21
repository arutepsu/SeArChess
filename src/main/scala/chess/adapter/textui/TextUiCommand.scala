package chess.adapter.textui

sealed trait TextUiCommand
object TextUiCommand:
  case object New                                     extends TextUiCommand
  final case class MoveCmd(from: String, to: String)  extends TextUiCommand
  final case class PromoteCmd(choice: String)         extends TextUiCommand
  case object Show                                    extends TextUiCommand
  case object Help                                    extends TextUiCommand
  case object Quit                                    extends TextUiCommand
