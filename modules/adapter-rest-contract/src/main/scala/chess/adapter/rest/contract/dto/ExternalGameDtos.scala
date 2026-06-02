package chess.adapter.rest.contract.dto

import ujson.Value

final case class StartExternalGameRequest(
    platform: String,
    externalGameId: String,
    mode: String,
    ourColor: String,
    opponentActorId: String
)

object StartExternalGameRequest:
  def fromJson(body: String): Either[String, StartExternalGameRequest] =
    try
      val json = ujson.read(body)
      val obj = json.obj
      Right(
        StartExternalGameRequest(
          platform = obj("platform").str,
          externalGameId = obj("externalGameId").str,
          mode = obj("mode").str,
          ourColor = obj("ourColor").str,
          opponentActorId = obj("opponentActorId").str
        )
      )
    catch case _: Exception => Left("Malformed JSON in request body")

final case class ExternalGameResponse(
    bindingId: String,
    platform: String,
    externalGameId: String,
    internalGameId: String,
    sessionId: String,
    ourActorId: String,
    status: String,
    statusReason: Option[String],
    lastProcessedPly: Int,
    createdAt: String,
    updatedAt: String
)

object ExternalGameResponse:
  def toJson(r: ExternalGameResponse): Value =
    ujson.Obj(
      "bindingId" -> ujson.Str(r.bindingId),
      "platform" -> ujson.Str(r.platform),
      "externalGameId" -> ujson.Str(r.externalGameId),
      "internalGameId" -> ujson.Str(r.internalGameId),
      "sessionId" -> ujson.Str(r.sessionId),
      "ourActorId" -> ujson.Str(r.ourActorId),
      "status" -> ujson.Str(r.status),
      "statusReason" -> r.statusReason.fold(ujson.Null: Value)(ujson.Str(_)),
      "lastProcessedPly" -> ujson.Num(r.lastProcessedPly.toDouble),
      "createdAt" -> ujson.Str(r.createdAt),
      "updatedAt" -> ujson.Str(r.updatedAt)
    )

final case class IngestMovesRequest(uciMoves: String)

object IngestMovesRequest:
  def fromJson(body: String): Either[String, IngestMovesRequest] =
    try Right(IngestMovesRequest(ujson.read(body).obj("uciMoves").str))
    catch case _: Exception => Left("Malformed JSON in request body")

final case class IngestMovesResponse(
    lastProcessedPly: Int,
    currentPlayer: String,
    gameStatus: String,
    nextAction: String
)

object IngestMovesResponse:
  def toJson(r: IngestMovesResponse): Value =
    ujson.Obj(
      "lastProcessedPly" -> ujson.Num(r.lastProcessedPly.toDouble),
      "currentPlayer" -> ujson.Str(r.currentPlayer),
      "gameStatus" -> ujson.Str(r.gameStatus),
      "nextAction" -> ujson.Str(r.nextAction)
    )

final case class AiMoveResponse(
    uciMove: String,
    lastProcessedPly: Int,
    gameStatus: String,
    nextAction: String
)

object AiMoveResponse:
  def toJson(r: AiMoveResponse): Value =
    ujson.Obj(
      "uciMove" -> ujson.Str(r.uciMove),
      "lastProcessedPly" -> ujson.Num(r.lastProcessedPly.toDouble),
      "gameStatus" -> ujson.Str(r.gameStatus),
      "nextAction" -> ujson.Str(r.nextAction)
    )

final case class FinishExternalGameRequest(reason: Option[String])

object FinishExternalGameRequest:
  def fromJson(body: String): Either[String, FinishExternalGameRequest] =
    if body.trim.isEmpty then Right(FinishExternalGameRequest(None))
    else
      try
        val json = ujson.read(body)
        Right(FinishExternalGameRequest(json.objOpt.flatMap(_.get("reason")).map(_.str)))
      catch case _: Exception => Left("Malformed JSON in request body")

type ExternalGameErrorResponse = ErrorResponse
