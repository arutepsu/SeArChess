package chess.streaming

import chess.streaming.DslCommand.*

object SearchessDslParser:
  private val UciMove = "^[a-h][1-8][a-h][1-8][qrbnQRBN]?$".r

  def parseLine(line: String, lineNumber: Int): Option[Either[DslParseError, DslCommand]] =
    val trimmed = line.trim
    if trimmed.isEmpty || trimmed.startsWith("#") then None
    else
      val parts = trimmed.split("\\s+").toList
      Some(parseParts(parts, lineNumber, line))

  private def parseParts(
      parts: List[String],
      lineNumber: Int,
      rawInput: String
  ): Either[DslParseError, DslCommand] =
    parts match
      case "session" :: sessionId :: Nil if sessionId.nonEmpty =>
        Right(SessionStartedCommand(lineNumber, sessionId))
      case "players" :: whiteName :: blackName :: Nil if whiteName.nonEmpty && blackName.nonEmpty =>
        Right(PlayersCommand(lineNumber, whiteName, blackName))
      case "move" :: playerName :: uciMove :: Nil if UciMove.matches(uciMove) =>
        Right(MoveCommand(lineNumber, playerName, uciMove.toLowerCase))
      case "status" :: Nil =>
        Right(StatusCommand(lineNumber))
      case "resign" :: playerName :: Nil if playerName.nonEmpty =>
        Right(ResignCommand(lineNumber, playerName))
      case "move" :: _ =>
        Left(DslParseError(lineNumber, rawInput, "expected: move <playerName> <uciMove>"))
      case "session" :: _ =>
        Left(DslParseError(lineNumber, rawInput, "expected: session <sessionId>"))
      case "players" :: _ =>
        Left(DslParseError(lineNumber, rawInput, "expected: players <whiteName> <blackName>"))
      case "resign" :: _ =>
        Left(DslParseError(lineNumber, rawInput, "expected: resign <playerName>"))
      case command :: _ =>
        Left(DslParseError(lineNumber, rawInput, s"unknown command: $command"))
      case Nil =>
        Left(DslParseError(lineNumber, rawInput, "empty command"))
