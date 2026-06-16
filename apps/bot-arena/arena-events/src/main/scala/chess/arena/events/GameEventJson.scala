package chess.arena.events

object GameEventJson:

  def encode(event: GameEvent): String = event match
    case e: GameStarted  => encodeGameStarted(e)
    case e: MovePlayed   => encodeMovePlayed(e)
    case e: GameFinished => encodeGameFinished(e)

  // TODO Phase 4: validate schemaVersion == GameEvent.CurrentSchemaVersion before dispatching (§14.1)
  def decode(line: String): Either[String, GameEvent] =
    parseObj(line).flatMap { json =>
      requiredString(json, "eventType").flatMap {
        case "GameStarted"  => decodeGameStarted(json)
        case "MovePlayed"   => decodeMovePlayed(json)
        case "GameFinished" => decodeGameFinished(json)
        case other          => Left(s"Unknown eventType: $other")
      }
    }

  private def encodeGameStarted(e: GameStarted): String =
    ujson.write(
      ujson.Obj(
        "schemaVersion"     -> e.schemaVersion,
        "eventType"         -> e.eventType,
        "eventId"           -> e.eventId,
        "timestamp"         -> e.timestamp,
        "tournamentId"      -> e.tournamentId,
        "gameId"            -> e.gameId,
        "whiteBotId"        -> e.whiteBotId,
        "blackBotId"        -> e.blackBotId,
        "whiteBotFamily"    -> e.whiteBotFamily,
        "blackBotFamily"    -> e.blackBotFamily,
        "whiteStrategyType" -> e.whiteStrategyType,
        "blackStrategyType" -> e.blackStrategyType,
        "whiteEngineType"   -> e.whiteEngineType,
        "blackEngineType"   -> e.blackEngineType,
        "whiteModelVersion" -> e.whiteModelVersion,
        "blackModelVersion" -> e.blackModelVersion
      )
    )

  private def encodeMovePlayed(e: MovePlayed): String =
    ujson.write(
      ujson.Obj(
        "schemaVersion"  -> e.schemaVersion,
        "eventType"      -> e.eventType,
        "eventId"        -> e.eventId,
        "timestamp"      -> e.timestamp,
        "tournamentId"   -> e.tournamentId,
        "gameId"         -> e.gameId,
        "botId"          -> e.botId,
        "moveUci"        -> e.moveUci,
        "plyNumber"      -> ujson.Num(e.plyNumber.toDouble),
        "durationMillis" -> ujson.Num(e.durationMillis.toDouble)
      )
    )

  private def encodeGameFinished(e: GameFinished): String =
    val obj = ujson.Obj(
      "schemaVersion"     -> e.schemaVersion,
      "eventType"         -> e.eventType,
      "eventId"           -> e.eventId,
      "timestamp"         -> e.timestamp,
      "tournamentId"      -> e.tournamentId,
      "gameId"            -> e.gameId,
      "whiteBotId"        -> e.whiteBotId,
      "blackBotId"        -> e.blackBotId,
      "result"            -> e.result,
      "whiteBotFamily"    -> e.whiteBotFamily,
      "blackBotFamily"    -> e.blackBotFamily,
      "whiteStrategyType" -> e.whiteStrategyType,
      "blackStrategyType" -> e.blackStrategyType,
      "whiteEngineType"   -> e.whiteEngineType,
      "blackEngineType"   -> e.blackEngineType,
      "whiteModelVersion" -> e.whiteModelVersion,
      "blackModelVersion" -> e.blackModelVersion,
      "totalMoves"        -> ujson.Num(e.totalMoves.toDouble),
      "totalPly"          -> ujson.Num(e.totalPly.toDouble),
      "durationMillis"    -> ujson.Num(e.durationMillis.toDouble),
      "terminationReason" -> e.terminationReason
    )
    e.winnerBotId.foreach(id => obj("winnerBotId") = ujson.Str(id))
    e.loserBotId.foreach(id => obj("loserBotId") = ujson.Str(id))
    ujson.write(obj)

  private def decodeGameStarted(json: ujson.Obj): Either[String, GameStarted] =
    for
      eventId           <- requiredString(json, "eventId")
      timestamp         <- requiredString(json, "timestamp")
      tournamentId      <- requiredString(json, "tournamentId")
      gameId            <- requiredString(json, "gameId")
      whiteBotId        <- requiredString(json, "whiteBotId")
      blackBotId        <- requiredString(json, "blackBotId")
      whiteBotFamily    <- requiredString(json, "whiteBotFamily")
      blackBotFamily    <- requiredString(json, "blackBotFamily")
      whiteStrategyType <- requiredString(json, "whiteStrategyType")
      blackStrategyType <- requiredString(json, "blackStrategyType")
      whiteEngineType   <- requiredString(json, "whiteEngineType")
      blackEngineType   <- requiredString(json, "blackEngineType")
      whiteModelVersion <- requiredString(json, "whiteModelVersion")
      blackModelVersion <- requiredString(json, "blackModelVersion")
    yield GameStarted(
      eventId,
      timestamp,
      tournamentId,
      gameId,
      whiteBotId,
      blackBotId,
      whiteBotFamily,
      blackBotFamily,
      whiteStrategyType,
      blackStrategyType,
      whiteEngineType,
      blackEngineType,
      whiteModelVersion,
      blackModelVersion
    )

  private def decodeMovePlayed(json: ujson.Obj): Either[String, MovePlayed] =
    for
      eventId        <- requiredString(json, "eventId")
      timestamp      <- requiredString(json, "timestamp")
      tournamentId   <- requiredString(json, "tournamentId")
      gameId         <- requiredString(json, "gameId")
      botId          <- requiredString(json, "botId")
      moveUci        <- requiredString(json, "moveUci")
      plyNumber      <- requiredInt(json, "plyNumber")
      durationMillis <- requiredLong(json, "durationMillis")
    yield MovePlayed(eventId, timestamp, tournamentId, gameId, botId, moveUci, plyNumber, durationMillis)

  private def decodeGameFinished(json: ujson.Obj): Either[String, GameFinished] =
    for
      eventId           <- requiredString(json, "eventId")
      timestamp         <- requiredString(json, "timestamp")
      tournamentId      <- requiredString(json, "tournamentId")
      gameId            <- requiredString(json, "gameId")
      whiteBotId        <- requiredString(json, "whiteBotId")
      blackBotId        <- requiredString(json, "blackBotId")
      result            <- requiredString(json, "result")
      whiteBotFamily    <- requiredString(json, "whiteBotFamily")
      blackBotFamily    <- requiredString(json, "blackBotFamily")
      whiteStrategyType <- requiredString(json, "whiteStrategyType")
      blackStrategyType <- requiredString(json, "blackStrategyType")
      whiteEngineType   <- requiredString(json, "whiteEngineType")
      blackEngineType   <- requiredString(json, "blackEngineType")
      whiteModelVersion <- requiredString(json, "whiteModelVersion")
      blackModelVersion <- requiredString(json, "blackModelVersion")
      totalMoves        <- requiredInt(json, "totalMoves")
      totalPly          <- requiredInt(json, "totalPly")
      durationMillis    <- requiredLong(json, "durationMillis")
      terminationReason <- requiredString(json, "terminationReason")
    yield GameFinished(
      eventId,
      timestamp,
      tournamentId,
      gameId,
      whiteBotId,
      blackBotId,
      winnerBotId = optionalString(json, "winnerBotId"),
      loserBotId = optionalString(json, "loserBotId"),
      result,
      whiteBotFamily,
      blackBotFamily,
      whiteStrategyType,
      blackStrategyType,
      whiteEngineType,
      blackEngineType,
      whiteModelVersion,
      blackModelVersion,
      totalMoves,
      totalPly,
      durationMillis,
      terminationReason
    )

  private def parseObj(text: String): Either[String, ujson.Obj] =
    scala.util.Try(ujson.read(text)).toOption
      .collect { case obj: ujson.Obj => obj }
      .toRight("Malformed JSON or not an object")

  private def requiredString(json: ujson.Obj, field: String): Either[String, String] =
    optionalString(json, field).toRight(s"Missing or invalid '$field'")

  private def requiredInt(json: ujson.Obj, field: String): Either[String, Int] =
    json.obj.get(field).collect { case ujson.Num(v) => v.toInt }
      .toRight(s"Missing or invalid '$field'")

  private def requiredLong(json: ujson.Obj, field: String): Either[String, Long] =
    json.obj.get(field).collect { case ujson.Num(v) => v.toLong }
      .toRight(s"Missing or invalid '$field'")

  private def optionalString(json: ujson.Obj, field: String): Option[String] =
    json.obj.get(field).collect { case ujson.Str(v) => v }
