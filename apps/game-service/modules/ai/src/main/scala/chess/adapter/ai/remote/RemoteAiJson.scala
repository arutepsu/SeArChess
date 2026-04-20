package chess.adapter.ai.remote

import ujson.Value

/** JSON codec for the future remote AI service DTOs.
 *
 *  Kept deliberately local to the adapter so transport JSON does not leak into
 *  the application AI port.
 */
object RemoteAiJson:

  def requestToJson(request: RemoteAiMoveSuggestionRequest): String =
    ujson.write(ujson.Obj(
      "requestId"  -> request.requestId,
      "gameId"     -> request.gameId,
      "sessionId"  -> request.sessionId,
      "sideToMove" -> request.sideToMove,
      "fen"        -> request.fen,
      "legalMoves" -> ujson.Arr.from(request.legalMoves.map(moveJson)),
      "engine"     -> ujson.Obj.from(request.engine.engineId.map(id => "engineId" -> (ujson.Str(id): Value))),
      "limits"     -> ujson.Obj(
        "timeoutMillis" -> ujson.Num(request.limits.timeoutMillis.toDouble)
      ),
      "metadata"   -> metadataJson(request.metadata)
    ))

  def responseFromJson(text: String): Either[String, RemoteAiMoveSuggestionResponse] =
    for
      json          <- parse(text)
      requestId     <- requiredString(json, "requestId")
      moveObj       <- requiredObj(json, "move")
      move          <- moveFromJson(moveObj)
      engineId       = optionalString(json, "engineId")
      engineVersion  = optionalString(json, "engineVersion")
      elapsed        = optionalInt(json, "elapsedMillis")
      confidence     = optionalDouble(json, "confidence")
    yield RemoteAiMoveSuggestionResponse(requestId, move, engineId, engineVersion, elapsed, confidence)

  def errorFromJson(text: String): Option[RemoteAiErrorResponse] =
    responseObj(text).flatMap { json =>
      for
        code    <- optionalString(json, "code")
        message <- optionalString(json, "message")
      yield RemoteAiErrorResponse(
        requestId = optionalString(json, "requestId").getOrElse(""),
        code      = code,
        message   = message
      )
    }

  private def moveJson(move: RemoteAiMoveDto): Value =
    val obj = ujson.Obj(
      "from" -> ujson.Str(move.from),
      "to"   -> ujson.Str(move.to)
    )
    move.promotion.foreach(p => obj("promotion") = ujson.Str(p))
    obj

  private def metadataJson(metadata: RemoteAiMetadata): Value =
    val obj = ujson.Obj("mode" -> ujson.Str(metadata.mode))
    metadata.testMode.foreach(mode => obj("testMode") = ujson.Str(mode))
    obj

  private def moveFromJson(json: ujson.Obj): Either[String, RemoteAiMoveDto] =
    for
      from <- requiredString(json, "from")
      to   <- requiredString(json, "to")
    yield RemoteAiMoveDto(from, to, optionalString(json, "promotion"))

  private def parse(text: String): Either[String, ujson.Obj] =
    responseObj(text).toRight("Malformed AI response JSON")

  private def responseObj(text: String): Option[ujson.Obj] =
    scala.util.Try(ujson.read(text)).toOption.collect { case obj: ujson.Obj => obj }

  private def requiredObj(json: ujson.Obj, field: String): Either[String, ujson.Obj] =
    json.obj.get(field).collect { case obj: ujson.Obj => obj }
      .toRight(s"Missing or invalid '$field' in AI response")

  private def requiredString(json: ujson.Obj, field: String): Either[String, String] =
    optionalString(json, field).toRight(s"Missing or invalid '$field' in AI response")

  private def optionalString(json: ujson.Obj, field: String): Option[String] =
    json.obj.get(field).collect { case ujson.Str(value) => value }

  private def optionalInt(json: ujson.Obj, field: String): Option[Int] =
    json.obj.get(field).collect { case ujson.Num(value) => value.toInt }

  private def optionalDouble(json: ujson.Obj, field: String): Option[Double] =
    json.obj.get(field).collect { case ujson.Num(value) => value.toDouble }
