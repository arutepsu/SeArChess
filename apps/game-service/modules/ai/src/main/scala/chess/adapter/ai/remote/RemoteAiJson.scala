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

  def requestFromJson(text: String): Either[String, RemoteAiMoveSuggestionRequest] =
    for
      json       <- parse(text, "Malformed AI request JSON")
      requestId  <- requiredString(json, "requestId", "AI request")
      gameId     <- requiredString(json, "gameId", "AI request")
      sessionId  <- requiredString(json, "sessionId", "AI request")
      sideToMove <- requiredString(json, "sideToMove", "AI request")
      fen        <- requiredString(json, "fen", "AI request")
      movesJson  <- requiredArr(json, "legalMoves", "AI request")
      moves      <- movesJson.value.toList.zipWithIndex.foldLeft[Either[String, List[RemoteAiMoveDto]]](Right(Nil)) {
                      case (Right(acc), (obj: ujson.Obj, _)) =>
                        moveFromJson(obj, "AI request").map(acc :+ _)
                      case (Right(_), (_, idx)) =>
                        Left(s"Missing or invalid 'legalMoves[$idx]' in AI request")
                      case (left @ Left(_), _) => left
                    }
      engineObj  <- optionalObj(json, "engine").toRight("Missing or invalid 'engine' in AI request")
      limitsObj  <- requiredObj(json, "limits", "AI request")
      timeout    <- requiredInt(limitsObj, "timeoutMillis", "AI request limits")
      metadataObj = optionalObj(json, "metadata").getOrElse(ujson.Obj("mode" -> ujson.Str("Unknown")))
      mode        = optionalString(metadataObj, "mode").getOrElse("Unknown")
    yield RemoteAiMoveSuggestionRequest(
      requestId  = requestId,
      gameId     = gameId,
      sessionId  = sessionId,
      sideToMove = sideToMove,
      fen        = fen,
      legalMoves = moves,
      engine     = RemoteAiEngineSelection(optionalString(engineObj, "engineId")),
      limits     = RemoteAiLimits(timeout),
      metadata   = RemoteAiMetadata(mode, optionalString(metadataObj, "testMode"))
    )

  def responseToJson(response: RemoteAiMoveSuggestionResponse): String =
    val obj = ujson.Obj(
      "requestId" -> ujson.Str(response.requestId),
      "move"      -> moveJson(response.move)
    )
    response.engineId.foreach(id => obj("engineId") = ujson.Str(id))
    response.engineVersion.foreach(v => obj("engineVersion") = ujson.Str(v))
    response.elapsedMillis.foreach(ms => obj("elapsedMillis") = ujson.Num(ms.toDouble))
    response.confidence.foreach(c => obj("confidence") = ujson.Num(c))
    ujson.write(obj)

  def errorToJson(error: RemoteAiErrorResponse): String =
    ujson.write(ujson.Obj(
      "requestId" -> error.requestId,
      "code"      -> error.code,
      "message"   -> error.message
    ))

  def responseFromJson(text: String): Either[String, RemoteAiMoveSuggestionResponse] =
    for
      json          <- parse(text, "Malformed AI response JSON")
      requestId     <- requiredString(json, "requestId", "AI response")
      moveObj       <- requiredObj(json, "move", "AI response")
      move          <- moveFromJson(moveObj, "AI response")
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

  private def moveFromJson(json: ujson.Obj, owner: String): Either[String, RemoteAiMoveDto] =
    for
      from <- requiredString(json, "from", owner)
      to   <- requiredString(json, "to", owner)
    yield RemoteAiMoveDto(from, to, optionalString(json, "promotion"))

  private def parse(text: String, error: String): Either[String, ujson.Obj] =
    responseObj(text).toRight(error)

  private def responseObj(text: String): Option[ujson.Obj] =
    scala.util.Try(ujson.read(text)).toOption.collect { case obj: ujson.Obj => obj }

  private def requiredObj(json: ujson.Obj, field: String, owner: String): Either[String, ujson.Obj] =
    optionalObj(json, field).toRight(s"Missing or invalid '$field' in $owner")

  private def optionalObj(json: ujson.Obj, field: String): Option[ujson.Obj] =
    json.obj.get(field).collect { case obj: ujson.Obj => obj }

  private def requiredArr(json: ujson.Obj, field: String, owner: String): Either[String, ujson.Arr] =
    json.obj.get(field).collect { case arr: ujson.Arr => arr }
      .toRight(s"Missing or invalid '$field' in $owner")

  private def requiredString(json: ujson.Obj, field: String, owner: String): Either[String, String] =
    optionalString(json, field).toRight(s"Missing or invalid '$field' in $owner")

  private def requiredInt(json: ujson.Obj, field: String, owner: String): Either[String, Int] =
    optionalInt(json, field).toRight(s"Missing or invalid '$field' in $owner")

  private def optionalString(json: ujson.Obj, field: String): Option[String] =
    json.obj.get(field).collect { case ujson.Str(value) => value }

  private def optionalInt(json: ujson.Obj, field: String): Option[Int] =
    json.obj.get(field).collect { case ujson.Num(value) => value.toInt }

  private def optionalDouble(json: ujson.Obj, field: String): Option[Double] =
    json.obj.get(field).collect { case ujson.Num(value) => value.toDouble }
