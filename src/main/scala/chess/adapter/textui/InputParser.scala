package chess.adapter.textui

object InputParser:

  def parse(input: String): Either[InputParseError, TextUiCommand] =
    val trimmed = input.trim
    if trimmed.isEmpty then Left(InputParseError.EmptyInput)
    else
      val parts = trimmed.split("\\s+").toList
      (parts: @unchecked) match
        case "new"  :: Nil               => Right(TextUiCommand.New)
        case "show" :: Nil               => Right(TextUiCommand.Show)
        case "help" :: Nil               => Right(TextUiCommand.Help)
        case "quit" :: Nil               => Right(TextUiCommand.Quit)
        case "move" :: from :: to :: Nil => Right(TextUiCommand.MoveCmd(from, to))
        case "move" :: _                 => Left(InputParseError.WrongArgumentCount("move"))
        case cmd    :: _                 => Left(InputParseError.UnknownCommand(cmd))
