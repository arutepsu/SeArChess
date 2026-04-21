package chess.history

import chess.application.query.game.GameClosure
import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, DrawReason}
import java.time.Instant
import java.util.UUID

object ArchiveRecordJson:

  def toJson(record: ArchiveRecord): ujson.Value =
    ujson.Obj(
      "gameId" -> record.gameId.value.toString,
      "sessionId" -> record.sessionId.value.toString,
      "mode" -> record.mode.toString,
      "whiteController" -> controllerString(record.whiteController),
      "blackController" -> controllerString(record.blackController),
      "closure" -> closureJson(record.closure),
      "pgn" -> record.pgn.fold(ujson.Null: ujson.Value)(ujson.Str(_)),
      "finalFen" -> record.finalFen.fold(ujson.Null: ujson.Value)(ujson.Str(_)),
      "createdAt" -> record.createdAt.toString,
      "closedAt" -> record.closedAt.toString,
      "materializedAt" -> record.materializedAt.toString
    )

  def fromJson(json: ujson.Value): Either[String, ArchiveRecord] =
    try
      Right(
        ArchiveRecord(
          gameId = GameId(UUID.fromString(json("gameId").str)),
          sessionId = SessionId(UUID.fromString(json("sessionId").str)),
          mode = parseMode(json("mode").str),
          whiteController = parseController(json("whiteController").str),
          blackController = parseController(json("blackController").str),
          closure = parseClosure(json("closure")),
          pgn = json("pgn") match
            case ujson.Null => None
            case value      => Some(value.str),
          finalFen = json("finalFen") match
            case ujson.Null => None
            case value      => Some(value.str),
          createdAt = Instant.parse(json("createdAt").str),
          closedAt = Instant.parse(json("closedAt").str),
          materializedAt = Instant.parse(json("materializedAt").str)
        )
      )
    catch case e: Exception => Left(s"Invalid archive record JSON: ${e.getMessage}")

  private def closureJson(closure: GameClosure): ujson.Value = closure match
    case GameClosure.Checkmate(winner) =>
      ujson.Obj("kind" -> "Checkmate", "winner" -> winner.toString, "drawReason" -> ujson.Null)
    case GameClosure.Resigned(winner) =>
      ujson.Obj("kind" -> "Resigned", "winner" -> winner.toString, "drawReason" -> ujson.Null)
    case GameClosure.Draw(reason) =>
      ujson.Obj("kind" -> "Draw", "winner" -> ujson.Null, "drawReason" -> reason.toString)
    case GameClosure.Cancelled =>
      ujson.Obj("kind" -> "Cancelled", "winner" -> ujson.Null, "drawReason" -> ujson.Null)

  private def controllerString(controller: chess.application.session.model.SideController): String =
    import chess.application.session.model.SideController.*
    controller match
      case HumanLocal       => "HumanLocal"
      case HumanRemote      => "HumanRemote"
      case AI(Some(engine)) => s"AI:$engine"
      case AI(None)         => "AI"

  private def parseClosure(json: ujson.Value): GameClosure =
    json("kind").str match
      case "Checkmate" => GameClosure.Checkmate(parseColor(json("winner").str))
      case "Resigned"  => GameClosure.Resigned(parseColor(json("winner").str))
      case "Draw"      => GameClosure.Draw(parseDrawReason(json("drawReason").str))
      case "Cancelled" => GameClosure.Cancelled
      case other       => throw IllegalArgumentException(s"unknown closure kind: $other")

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
