package chess.adapter.event.kafka

import chess.application.event.AppEvent
import chess.application.session.model.SessionIds.GameId
import chess.domain.model.{Color, DrawReason, GameStatus, Move, PieceType}

import java.time.Instant
import java.util.UUID

object GameEventEnvelope:
  val Producer: String      = "game-service"
  val AggregateType: String = "Game"

  val GameCreated: String  = "GameCreated"
  val MoveApplied: String  = "MoveApplied"
  val MoveRejected: String = "MoveRejected"
  val GameFinished: String = "GameFinished"

  val SupportedTypes: Set[String] =
    Set(GameCreated, MoveApplied, MoveRejected, GameFinished)

  def fromAppEvent(
      event: AppEvent,
      occurredAt: Instant = Instant.now(),
      correlationId: Option[String] = None,
      causationId: Option[String] = None
  ): Option[EventEnvelope[ujson.Value]] =
    event match
      case e: AppEvent.SessionCreated =>
        Some(envelope(GameCreated, e.gameId, occurredAt, correlationId, causationId, sessionCreatedPayload(e)))
      case e: AppEvent.MoveApplied =>
        Some(envelope(MoveApplied, e.gameId, occurredAt, correlationId, causationId, moveAppliedPayload(e)))
      case e: AppEvent.MoveRejected =>
        Some(envelope(MoveRejected, e.gameId, occurredAt, correlationId, causationId, moveRejectedPayload(e)))
      case e: AppEvent.GameFinished =>
        Some(envelope(GameFinished, e.gameId, occurredAt, correlationId, causationId, gameFinishedPayload(e)))
      case _ => None

  def validate(envelope: EventEnvelope[ujson.Value]): Either[String, EventEnvelope[ujson.Value]] =
    if envelope.eventVersion != EventEnvelope.SupportedVersion then
      Left(s"unsupported eventVersion: ${envelope.eventVersion}")
    else if !SupportedTypes.contains(envelope.eventType) then
      Left(s"unsupported eventType: ${envelope.eventType}")
    else if envelope.aggregateType != AggregateType then
      Left(s"unsupported aggregateType: ${envelope.aggregateType}")
    else
      payloadGameId(envelope.payload).flatMap { payloadId =>
        Either.cond(payloadId == envelope.aggregateId, envelope, "aggregateId does not match payload.gameId")
      }

  private def envelope(
      eventType: String,
      gameId: GameId,
      occurredAt: Instant,
      correlationId: Option[String],
      causationId: Option[String],
      payload: ujson.Value
  ): EventEnvelope[ujson.Value] =
    EventEnvelope(
      eventId       = UUID.randomUUID().toString,
      eventType     = eventType,
      eventVersion  = EventEnvelope.SupportedVersion,
      occurredAt    = occurredAt,
      producer      = Producer,
      correlationId = correlationId.getOrElse(UUID.randomUUID().toString),
      causationId   = causationId,
      aggregateType = AggregateType,
      aggregateId   = gameId.value.toString,
      payload       = payload
    )

  private def sessionCreatedPayload(e: AppEvent.SessionCreated): ujson.Obj =
    ujson.Obj(
      "type" -> "game.session.created.v1",
      "sessionId" -> e.sessionId.value.toString,
      "gameId" -> e.gameId.value.toString
    )

  private def moveAppliedPayload(e: AppEvent.MoveApplied): ujson.Obj =
    ujson.Obj(
      "type" -> "game.move.applied.v1",
      "sessionId" -> e.sessionId.value.toString,
      "gameId" -> e.gameId.value.toString,
      "move" -> moveJson(e.move),
      "playerWhoMoved" -> colorStr(e.playerWhoMoved)
    )

  private def moveRejectedPayload(e: AppEvent.MoveRejected): ujson.Obj =
    ujson.Obj(
      "type" -> "game.move.rejected.v1",
      "sessionId" -> e.sessionId.value.toString,
      "gameId" -> e.gameId.value.toString,
      "move" -> moveJson(e.move),
      "reason" -> e.reason
    )

  private def gameFinishedPayload(e: AppEvent.GameFinished): ujson.Obj =
    e.status match
      case GameStatus.Checkmate(winner) =>
        ujson.Obj(
          "type" -> "game.finished.v1",
          "sessionId" -> e.sessionId.value.toString,
          "gameId" -> e.gameId.value.toString,
          "result" -> "Checkmate",
          "winner" -> colorStr(winner),
          "drawReason" -> ujson.Null
        )
      case GameStatus.Draw(reason) =>
        ujson.Obj(
          "type" -> "game.finished.v1",
          "sessionId" -> e.sessionId.value.toString,
          "gameId" -> e.gameId.value.toString,
          "result" -> "Draw",
          "winner" -> ujson.Null,
          "drawReason" -> drawReasonStr(reason)
        )
      case other =>
        throw IllegalArgumentException(s"GameFinished event carries non-terminal status: $other")

  private def moveJson(move: Move): ujson.Obj =
    ujson.Obj(
      "from" -> move.from.toString,
      "to" -> move.to.toString,
      "promotion" -> move.promotion.fold[ujson.Value](ujson.Null)(pt => ujson.Str(pieceTypeStr(pt)))
    )

  private def payloadGameId(payload: ujson.Value): Either[String, String] =
    try Right(payload("gameId").str)
    catch case e: Exception => Left(s"payload.gameId is missing or invalid: ${e.getMessage}")

  private def colorStr(color: Color): String = color match
    case Color.White => "White"
    case Color.Black => "Black"

  private def drawReasonStr(reason: DrawReason): String = reason match
    case DrawReason.Stalemate => "Stalemate"

  private def pieceTypeStr(pieceType: PieceType): String = pieceType match
    case PieceType.Queen  => "Queen"
    case PieceType.Rook   => "Rook"
    case PieceType.Bishop => "Bishop"
    case PieceType.Knight => "Knight"
    case PieceType.King   => "King"
    case PieceType.Pawn   => "Pawn"
