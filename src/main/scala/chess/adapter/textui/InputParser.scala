package chess.adapter.textui

import chess.domain.model.PieceType

object InputParser:

  // Closure-Beispiel: Funktion, die ein erlaubtes Kommando-Set aus dem Scope verwendet
  def makeCommandValidator(allowed: Set[String]): String => Boolean =
    (cmd: String) => allowed.contains(cmd)

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
            case "q" | "queen"  => Right(TextUiCommand.PromoteCmd(PieceType.Queen))
            case "r" | "rook"   => Right(TextUiCommand.PromoteCmd(PieceType.Rook))
            case "b" | "bishop" => Right(TextUiCommand.PromoteCmd(PieceType.Bishop))
            case "n" | "knight" => Right(TextUiCommand.PromoteCmd(PieceType.Knight))
            case _              => Left(InputParseError.InvalidPromotionToken(token))
        case "promote" :: _                 => Left(InputParseError.WrongArgumentCount("promote"))
        case cmd       :: _                 => Left(InputParseError.UnknownCommand(cmd))
