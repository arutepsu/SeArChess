package chess.lichessbridge

import scala.util.Try

/** Pure NDJSON parsing for Lichess bot event and game event streams.
  *
  * Rules:
  *   - Blank lines (Lichess keep-alive pings) produce Unknown("keep-alive", "").
  *   - Unrecognised event types produce Unknown(type, raw) — stream continues.
  *   - Any parse failure produces Left(ParseError) — stream continues; caller decides what to do.
  *   - No IO, no side effects. Fully testable without a network.
  */
object LichessNdjsonParser:

  def parseBotEventLine(line: String): Either[ParseError, LichessBotEvent] =
    if line.isBlank then Right(LichessBotEvent.Unknown("keep-alive", ""))
    else
      Try(doParseBotEvent(line)) match
        case scala.util.Success(event) => Right(event)
        case scala.util.Failure(e)     => Left(ParseError(e.getMessage.take(200), line.take(300)))

  def parseGameEventLine(line: String): Either[ParseError, LichessGameEvent] =
    if line.isBlank then Right(LichessGameEvent.Unknown("keep-alive", ""))
    else
      Try(doParseGameEvent(line)) match
        case scala.util.Success(event) => Right(event)
        case scala.util.Failure(e)     => Left(ParseError(e.getMessage.take(200), line.take(300)))

  // ── Private parsers ────────────────────────────────────────────────────────

  private def doParseBotEvent(line: String): LichessBotEvent =
    val json = ujson.read(line)
    json.obj.get("type").flatMap(_.strOpt) match
      case Some("challenge") =>
        val c           = json("challenge")
        val challengeId = c("id").str
        val challenger  = c("challenger")("name").str
        val variant     = c.obj.get("variant").flatMap(_.obj.get("key")).flatMap(_.strOpt).getOrElse("standard")
        val speed       = c.obj.get("speed").flatMap(_.strOpt).getOrElse("correspondence")
        val rated       = c.obj.get("rated").flatMap(_.boolOpt).getOrElse(false)
        val tc = c.obj.get("timeControl") match
          case Some(tcJson) =>
            LichessTimeControl(
              tcType    = tcJson.obj.get("type").flatMap(_.strOpt).getOrElse("unlimited"),
              limit     = tcJson.obj.get("limit").flatMap(_.numOpt).map(_.toInt),
              increment = tcJson.obj.get("increment").flatMap(_.numOpt).map(_.toInt)
            )
          case None => LichessTimeControl("unlimited", None, None)
        val color = c.obj.get("color").flatMap(_.strOpt)
        LichessBotEvent.ChallengeCreated(
          LichessChallenge(challengeId, challenger, variant, speed, rated, tc, color)
        )

      case Some("challengeCanceled") =>
        val challengeId = json("challenge")("id").str
        LichessBotEvent.ChallengeCanceled(challengeId)

      case Some("gameStart") =>
        val g        = json("game")
        val gameId   = g("gameId").str
        val fullId   = g.obj.get("fullId").flatMap(_.strOpt).getOrElse(gameId)
        val url      = s"https://lichess.org/$fullId"
        val opponent = g.obj.get("opponent").flatMap(_.obj.get("username")).flatMap(_.strOpt).getOrElse("unknown")
        val side     = g.obj.get("color").flatMap(_.strOpt)
        LichessBotEvent.GameStart(LichessGameRef(gameId, url, opponent, side))

      case Some("gameFinish") =>
        val g        = json("game")
        val gameId   = g("gameId").str
        val fullId   = g.obj.get("fullId").flatMap(_.strOpt).getOrElse(gameId)
        val url      = s"https://lichess.org/$fullId"
        val opponent = g.obj.get("opponent").flatMap(_.obj.get("username")).flatMap(_.strOpt).getOrElse("unknown")
        val side     = g.obj.get("color").flatMap(_.strOpt)
        LichessBotEvent.GameFinish(LichessGameRef(gameId, url, opponent, side))

      case Some(other) => LichessBotEvent.Unknown(other, line.take(300))
      case None        => LichessBotEvent.Unknown("no-type", line.take(300))

  private def doParseGameEvent(line: String): LichessGameEvent =
    val json = ujson.read(line)
    json.obj.get("type").flatMap(_.strOpt) match
      case Some("gameFull") =>
        val id     = json.obj.get("id").flatMap(_.strOpt).getOrElse("")
        val white  = json.obj.get("white").flatMap(_.obj.get("name")).flatMap(_.strOpt).getOrElse("unknown")
        val black  = json.obj.get("black").flatMap(_.obj.get("name")).flatMap(_.strOpt).getOrElse("unknown")
        val fen    = json.obj.get("initialFen").flatMap(_.strOpt).getOrElse("startpos")
        val state  = json("state")
        val moves  = state.obj.get("moves").flatMap(_.strOpt).getOrElse("")
        val wtime  = state.obj.get("wtime").flatMap(_.numOpt).map(_.toInt).getOrElse(0)
        val btime  = state.obj.get("btime").flatMap(_.numOpt).map(_.toInt).getOrElse(0)
        val status = state.obj.get("status").flatMap(_.strOpt).getOrElse("unknown")
        LichessGameEvent.GameFull(id, white, black, fen, moves, wtime, btime, status)

      case Some("gameState") =>
        val moves  = json.obj.get("moves").flatMap(_.strOpt).getOrElse("")
        val wtime  = json.obj.get("wtime").flatMap(_.numOpt).map(_.toInt).getOrElse(0)
        val btime  = json.obj.get("btime").flatMap(_.numOpt).map(_.toInt).getOrElse(0)
        val status = json.obj.get("status").flatMap(_.strOpt).getOrElse("unknown")
        LichessGameEvent.GameState(moves, wtime, btime, status)

      case Some("chatLine") =>
        val username = json.obj.get("username").flatMap(_.strOpt).getOrElse("unknown")
        val text     = json.obj.get("text").flatMap(_.strOpt).getOrElse("")
        LichessGameEvent.ChatLine(username, text)

      case Some("opponentGone") =>
        val gone    = json.obj.get("gone").flatMap(_.boolOpt).getOrElse(false)
        val claimIn = json.obj.get("claimWinInSeconds").flatMap(_.numOpt).map(_.toInt)
        LichessGameEvent.OpponentGone(gone, claimIn)

      case Some(other) => LichessGameEvent.Unknown(other, line.take(300))
      case None        => LichessGameEvent.Unknown("no-type", line.take(300))
