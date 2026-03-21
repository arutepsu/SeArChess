package chess.adapter.textui

object InputParser:

  def parse(input: String): Either[InputParseError, TextUiCommand] =
    val trimmed = input.trim
    if trimmed.isEmpty then Left(InputParseError.EmptyInput)
    else
      val parts = trimmed.split("\\s+").toList
      (parts: @unchecked) match
        case "new"     :: Nil               => Right(TextUiCommand.New)
        case "show"    :: Nil               => Right(TextUiCommand.Show)
        case "help"    :: Nil               => Right(TextUiCommand.Help)
        case "quit"    :: Nil               => Right(TextUiCommand.Quit)
        case "move"    :: from :: to :: Nil => Right(TextUiCommand.MoveCmd(from, to))
        case "move"    :: _                 => Left(InputParseError.WrongArgumentCount("move"))
        case "promote" :: token :: Nil      =>
          token.toLowerCase match
            case "q" | "queen"  => Right(TextUiCommand.PromoteCmd("q"))
            case "r" | "rook"   => Right(TextUiCommand.PromoteCmd("r"))
            case "b" | "bishop" => Right(TextUiCommand.PromoteCmd("b"))
            case "n" | "knight" => Right(TextUiCommand.PromoteCmd("n"))
            case _              => Left(InputParseError.InvalidPromotionToken(token))
        case "promote" :: _                 => Left(InputParseError.WrongArgumentCount("promote"))
        case cmd       :: _                 => Left(InputParseError.UnknownCommand(cmd))
