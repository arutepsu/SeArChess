package chess.history

import chess.application.query.game.{GameArchiveSnapshot, GameClosure, GameView}
import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, DrawReason, GameStatus, Move, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, EnPassantState}
import java.time.Instant
import java.util.UUID

object ArchiveSnapshotJson:

  def fromJson(body: String): Either[String, GameArchiveSnapshot] =
    try
      val json = ujson.read(body)
      val gameJson = json("finalState")("game")
      val gameId = GameId(UUID.fromString(json("gameId").str))
      val state = GameView(
        gameId = gameId,
        currentPlayer = parseColor(gameJson("currentPlayer").str),
        status = parseStatus(gameJson),
        board = gameJson("board").arr.toSeq.map(pieceFromJson),
        moveHistory = gameJson("moveHistory").arr.toList.map(moveFromJson),
        castlingRights = castlingFromJson(json("finalState")("castlingRights")),
        enPassantState = enPassantFromJson(json("finalState")("enPassant")),
        halfmoveClock = gameJson("halfmoveClock").num.toInt,
        fullmoveNumber = gameJson("fullmoveNumber").num.toInt,
        legalMoves = Set.empty
      )

      Right(
        GameArchiveSnapshot(
          sessionId = SessionId(UUID.fromString(json("sessionId").str)),
          gameId = gameId,
          mode = parseMode(json("mode").str),
          whiteController = parseController(json("whiteController").str),
          blackController = parseController(json("blackController").str),
          closure = closureFromJson(json("closure")),
          finalState = state,
          createdAt = Instant.parse(json("createdAt").str),
          closedAt = Instant.parse(json("closedAt").str)
        )
      )
    catch case e: Exception => Left(s"Invalid archive snapshot JSON: ${e.getMessage}")

  private def closureFromJson(json: ujson.Value): GameClosure =
    json("kind").str match
      case "Checkmate" => GameClosure.Checkmate(parseColor(json("winner").str))
      case "Resigned"  => GameClosure.Resigned(parseColor(json("winner").str))
      case "Draw"      => GameClosure.Draw(parseDrawReason(json("drawReason").str))
      case "Cancelled" => GameClosure.Cancelled
      case other       => throw IllegalArgumentException(s"unknown closure kind: $other")

  private def pieceFromJson(json: ujson.Value): (Position, Piece) =
    parsePosition(json("square").str) -> Piece(
      parseColor(json("color").str),
      parsePieceType(json("pieceType").str)
    )

  private def moveFromJson(json: ujson.Value): Move =
    Move(
      from = parsePosition(json("from").str),
      to = parsePosition(json("to").str),
      promotion = json("promotion") match
        case ujson.Null => None
        case value      => Some(parsePieceType(value.str))
    )

  private def castlingFromJson(json: ujson.Value): CastlingRights =
    CastlingRights(
      whiteKingSide = json("whiteKingSide").bool,
      whiteQueenSide = json("whiteQueenSide").bool,
      blackKingSide = json("blackKingSide").bool,
      blackQueenSide = json("blackQueenSide").bool
    )

  private def enPassantFromJson(json: ujson.Value): Option[EnPassantState] =
    json match
      case ujson.Null => None
      case value =>
        Some(
          EnPassantState(
            targetSquare = parsePosition(value("targetSquare").str),
            capturablePawnSquare = parsePosition(value("capturablePawnSquare").str),
            pawnColor = parseColor(value("pawnColor").str)
          )
        )

  private def parseStatus(json: ujson.Value): GameStatus =
    json("status").str match
      case "Ongoing"   => GameStatus.Ongoing(json("inCheck").bool)
      case "Checkmate" => GameStatus.Checkmate(parseColor(json("winner").str))
      case "Resigned"  => GameStatus.Resigned(parseColor(json("winner").str))
      case "Draw"      => GameStatus.Draw(parseDrawReason(json("drawReason").str))
      case other       => throw IllegalArgumentException(s"unknown status: $other")

  private def parseMode(value: String): SessionMode =
    SessionMode.values
      .find(_.toString == value)
      .getOrElse(throw IllegalArgumentException(s"unknown mode: $value"))

  private def parseController(value: String): SideController =
    value match
      case "HumanLocal"             => SideController.HumanLocal
      case "HumanRemote"            => SideController.HumanRemote
      case "AI"                     => SideController.AI(None)
      case v if v.startsWith("AI:") => SideController.AI(Some(v.stripPrefix("AI:")))
      case other                    => throw IllegalArgumentException(s"unknown controller: $other")

  private def parseColor(value: String): Color =
    Color.values
      .find(_.toString == value)
      .getOrElse(throw IllegalArgumentException(s"unknown color: $value"))

  private def parseDrawReason(value: String): DrawReason =
    DrawReason.values
      .find(_.toString == value)
      .getOrElse(throw IllegalArgumentException(s"unknown draw reason: $value"))

  private def parsePieceType(value: String): PieceType =
    PieceType.values
      .find(_.toString == value)
      .getOrElse(throw IllegalArgumentException(s"unknown piece type: $value"))

  private def parsePosition(value: String): Position =
    Position
      .fromAlgebraic(value)
      .fold(err => throw IllegalArgumentException(err.toString), identity)
