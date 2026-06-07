package chess.lichessbot

import sttp.client3.*
import ujson.Value

import java.io.{BufferedReader, InputStreamReader}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Latest Lichess state cached only for Web UI fan-out. */
case class GameStateCache(
    moves: String,
    isWhite: Boolean,
    wtime: Option[Long],
    btime: Option[Long],
    lastUpdated: Long
)

/** Lichess Bot client.
  *
  * Game authority lives in Game Service. This bot only streams Lichess events, forwards cumulative
  * UCI move history to `/external-games`, and submits the `uciMove` returned by Game Service.
  */
class LichessBot(
    val token: String,
    acceptIncomingChallenges: Boolean = false,
    gameServiceClient: Option[GameServiceClient] =
      GameServiceClientConfig.fromEnv().map(GameServiceClient(_))
)(implicit ec: ExecutionContext):

  private val backend = HttpClientSyncBackend()
  private val httpClient = HttpClient.newHttpClient()

  // Cache to store latest visible state for Web UI clients. Not used for game authority.
  private val activeGames = new ConcurrentHashMap[String, GameStateCache]()

  // UX-only chat guards.
  private val greetedGames = new ConcurrentHashMap[String, java.lang.Boolean]()
  private val finishedGames = new ConcurrentHashMap[String, java.lang.Boolean]()

  private val wsServer = new LichessBotWebSocketServer(
    9323,
    conn => {
      activeGames.forEach { (gameId, cache) =>
        val botColor = if cache.isWhite then "white" else "black"
        val json = ujson.Obj(
          "gameId" -> gameId,
          "moves" -> cache.moves,
          "botColor" -> botColor,
          "wtime" -> cache.wtime.map(ujson.Num(_)).getOrElse(ujson.Null),
          "btime" -> cache.btime.map(ujson.Num(_)).getOrElse(ujson.Null),
          "lastUpdated" -> cache.lastUpdated
        )
        try conn.send(ujson.write(json))
        catch
          case e: Exception =>
            println(s"[LichessBot] Failed to send initial state to client: ${e.getMessage}")
      }
    }
  )

  def start(): Unit =
    println("[LichessBot] Loading account details...")
    val botId = fetchBotId()
    println(s"[LichessBot] Successfully logged in as bot ID: $botId")
    gameServiceClient match
      case Some(client) =>
        println(
          s"[LichessBot] Game Service external-game integration enabled: baseUrl=${client.config.baseUrl}, platform=${client.config.platform}, actorId=${client.config.actorId}"
        )
      case None =>
        println(
          "[LichessBot] Game Service external-game integration disabled: EXTERNAL_GAME_BOT_API_KEY is not configured. The bot will not make moves."
        )

    println("[LichessBot] Starting WebSocket Server on port 9323...")
    wsServer.start()

    println("[LichessBot] Starting event stream listening...")
    connectToEventStream(botId)

  private def fetchBotId(): String =
    val request = basicRequest
      .get(uri"https://lichess.org/api/account")
      .header("Authorization", s"Bearer $token")

    val response = request.send(backend)
    response.body match
      case Right(bodyStr) => ujson.read(bodyStr)("id").str.toLowerCase
      case Left(error)    => throw RuntimeException(s"Failed to fetch Lichess account info: $error")

  private def connectToEventStream(botId: String): Unit =
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create("https://lichess.org/api/stream/event"))
      .header("Authorization", s"Bearer $token")
      .GET()
      .build()

    try
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
      if response.statusCode() == 200 then
        val reader = BufferedReader(InputStreamReader(response.body(), "UTF-8"))
        try readEventLines(reader, botId)
        finally
          reader.close()
          response.body().close()
      else
        println(
          s"[LichessBot] Event Stream returned status ${response.statusCode()}. Reconnecting in 5 seconds..."
        )
        Thread.sleep(5000)
        connectToEventStream(botId)
    catch
      case NonFatal(e) =>
        println(
          s"[LichessBot] Event Stream disconnected: ${e.getMessage}. Reconnecting in 5 seconds..."
        )
        Thread.sleep(5000)
        connectToEventStream(botId)

  @scala.annotation.tailrec
  private def readEventLines(reader: BufferedReader, botId: String): Unit =
    val line = reader.readLine()
    if line != null then
      val trimmed = line.trim
      if trimmed.nonEmpty then
        try handleEvent(trimmed, botId)
        catch
          case NonFatal(e) =>
            println(
              s"[LichessBot] [Event Error] Error handling event: $trimmed, error: ${e.getMessage}"
            )
      readEventLines(reader, botId)

  private def handleEvent(line: String, botId: String): Unit =
    val json = ujson.read(line)
    json("type").str match
      case "challenge" =>
        val challengeId = json("challenge")("id").str
        val challenger = json("challenge")("challenger")("id").str
        if acceptIncomingChallenges then
          println(s"[LichessBot] Received challenge $challengeId from $challenger. Accepting...")
          acceptChallenge(challengeId)
        else
          println(s"[LichessBot] Received challenge $challengeId from $challenger. Ignoring because incoming challenges are disabled.")

      case "gameStart" =>
        val gameId = json("game")("id").str
        println(s"[LichessBot] Game started: $gameId. Starting game stream...")
        val _ = Future {
          try connectToGameStream(gameId, botId)
          catch
            case NonFatal(e) =>
              println(
                s"[LichessBot] [Game Stream Error] Game $gameId stream finished with error: ${e.getMessage}"
              )
        }

      case other =>
        println(s"[LichessBot] Ignored event type: $other")

  private def acceptChallenge(challengeId: String): Unit =
    val request = basicRequest
      .post(uri"https://lichess.org/api/challenge/$challengeId/accept")
      .header("Authorization", s"Bearer $token")

    val response = request.send(backend)
    val _ = response.body match
      case Right(_)  => println(s"[LichessBot] Successfully accepted challenge $challengeId")
      case Left(err) => println(s"[LichessBot] Failed to accept challenge $challengeId: $err")

  private def connectToGameStream(gameId: String, botId: String): Unit =
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(s"https://lichess.org/api/bot/game/stream/$gameId"))
      .header("Authorization", s"Bearer $token")
      .GET()
      .build()

    try
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
      if response.statusCode() == 200 then
        val reader = BufferedReader(InputStreamReader(response.body(), "UTF-8"))
        try readGameLines(reader, gameId, botId, isWhite = false)
        finally
          reader.close()
          response.body().close()
          val _ = activeGames.remove(gameId)
          println(s"[LichessBot] [Game $gameId] Stream closed.")
      else
        println(s"[LichessBot] [Game $gameId] Game Stream returned status ${response.statusCode()}")
    catch
      case NonFatal(e) =>
        println(s"[LichessBot] [Game $gameId] Failed to connect to game stream: ${e.getMessage}")

  @scala.annotation.tailrec
  private def readGameLines(
      reader: BufferedReader,
      gameId: String,
      botId: String,
      isWhite: Boolean
  ): Unit =
    val line = reader.readLine()
    if line != null then
      val trimmed = line.trim
      val nextIsWhite =
        if trimmed.nonEmpty then
          try handleGameLine(gameId, botId, isWhite, ujson.read(trimmed))
          catch
            case NonFatal(e) =>
              println(
                s"[LichessBot] [Game $gameId] Error parsing game stream line: $trimmed, error: ${e.getMessage}"
              )
              isWhite
        else isWhite
      readGameLines(reader, gameId, botId, nextIsWhite)

  private def handleGameLine(
      gameId: String,
      botId: String,
      isWhite: Boolean,
      json: Value
  ): Boolean =
    json("type").str match
      case "gameFull" =>
        val determinedColor = json("white")("id").str.toLowerCase == botId
        val stateJson = json("state")
        val moves = cumulativeMoves(stateJson)
        val status = statusValue(stateJson)
        val opponentId = opponentActorId(json, botId, determinedColor)
        val (wtimeOpt, btimeOpt) = clockTimes(stateJson)
        println(
          s"[LichessBot] [Game $gameId] Initialized. Bot Color: ${if determinedColor then "White" else "Black"}. Status: $status"
        )
        val started = startExternalGame(gameId, determinedColor, opponentId)
        if started then
          processExternalGameState(gameId, moves, determinedColor, status, source = "gameFull")
        broadcastGameState(gameId, moves, determinedColor, wtimeOpt, btimeOpt)
        handleTerminalUx(gameId, status)
        determinedColor

      case "gameState" =>
        val moves = cumulativeMoves(json)
        val status = statusValue(json)
        val (wtimeOpt, btimeOpt) = clockTimes(json)
        processExternalGameState(gameId, moves, isWhite, status, source = "gameState")
        broadcastGameState(gameId, moves, isWhite, wtimeOpt, btimeOpt)
        handleTerminalUx(gameId, status)
        isWhite

      case _ =>
        isWhite

  private def processExternalGameState(
      gameId: String,
      moves: String,
      isWhite: Boolean,
      status: String,
      source: String
  ): Unit =
    if status == "started" then
      gameServiceClient match
        case Some(client) =>
          client.ingestMoves(client.config.platform, gameId, moves) match
            case Right(result) =>
              println(
                s"[LichessBot] [Game $gameId] Game Service ingested $source moves='${moves.trim}', lastProcessedPly=${result.lastProcessedPly}, nextAction=${result.nextAction}"
              )
              maybeSendGreeting(gameId, moves)
              result.nextAction match
                case NextAction.AwaitExternalMove =>
                  println(s"[LichessBot] [Game $gameId] Awaiting next external move.")
                case NextAction.TriggerAI =>
                  requestAndSubmitAiMove(client, gameId, moves, isWhite)
                case NextAction.GameFinished =>
                  finishExternalGame(gameId, "Game Service reported terminal state")
            case Left(error) =>
              logGameServiceError(gameId, "move ingestion", error)
        case None =>
          println(
            s"[LichessBot] [Game $gameId] Game Service client is not configured. Refusing to move to avoid split-brain state."
          )
    else if isTerminalStatus(status) then finishExternalGame(gameId, status)
    else
      println(
        s"[LichessBot] [Game $gameId] Game is not active (Status: $status). Skipping turn processing."
      )

  private def startExternalGame(gameId: String, isWhite: Boolean, opponentActorId: String): Boolean =
    gameServiceClient match
      case Some(client) =>
        val color = if isWhite then "White" else "Black"
        client.startExternalGame(gameId, color, opponentActorId) match
          case Right(binding) =>
            println(
              s"[LichessBot] [Game $gameId] External game binding ready: ${binding.platform}/${binding.externalGameId}, status=${binding.status}"
            )
            true
          case Left(error) =>
            logGameServiceError(gameId, "external game start", error)
            false
      case None =>
        println(
          s"[LichessBot] [Game $gameId] Cannot start external game binding: Game Service client is not configured."
        )
        false

  private def requestAndSubmitAiMove(
      client: GameServiceClient,
      gameId: String,
      moves: String,
      isWhite: Boolean
  ): Unit =
    client.requestAiMove(client.config.platform, gameId) match
      case Right(aiMove) if aiMove.uciMove.trim.nonEmpty =>
        println(
          s"[LichessBot] [Game $gameId] Game Service selected move '${aiMove.uciMove}'. Submitting to Lichess..."
        )
        submitMove(gameId, aiMove.uciMove)
        val newMoves = if moves.trim.isEmpty then aiMove.uciMove else s"${moves.trim} ${aiMove.uciMove}"
        val currentCacheOpt = Option(activeGames.get(gameId))
        val (wtime, btime) =
          currentCacheOpt.map(c => (c.wtime, c.btime)).getOrElse((None, None))
        broadcastGameState(gameId, newMoves, isWhite, wtime, btime)
      case Right(_) =>
        println(s"[LichessBot] [Game $gameId] Game Service ai-move returned an empty move. Refusing to move.")
      case Left(error) =>
        logGameServiceError(gameId, "AI move request", error)

  private def finishExternalGame(gameId: String, reason: String): Unit =
    gameServiceClient.foreach { client =>
      client.finishExternalGame(client.config.platform, gameId, Some(reason)) match
        case Right(binding) =>
          println(s"[LichessBot] [Game $gameId] External game marked finished in Game Service: ${binding.status}")
        case Left(GameServiceClientError.Rejected(409, _)) =>
          println(s"[LichessBot] [Game $gameId] External game was already inactive in Game Service.")
        case Left(error) =>
          logGameServiceError(gameId, "external game finish", error)
    }

  private def submitMove(gameId: String, move: String): Unit =
    val request = basicRequest
      .post(uri"https://lichess.org/api/bot/game/$gameId/move/$move")
      .header("Authorization", s"Bearer $token")

    val response = request.send(backend)
    val _ = response.body match
      case Right(_) =>
        println(s"[LichessBot] [Game $gameId] Move $move successfully submitted")
      case Left(err) =>
        println(s"[LichessBot] [Game $gameId] Failed to submit move $move: $err")

  private def sendChatMessage(gameId: String, message: String): Unit =
    val request = basicRequest
      .post(uri"https://lichess.org/api/bot/game/$gameId/chat")
      .header("Authorization", s"Bearer $token")
      .body(Map("room" -> "player", "text" -> message))

    val response = request.send(backend)
    val _ = response.body match
      case Right(_) =>
        println(s"[LichessBot] [Game $gameId] Chat message sent: '$message'")
      case Left(err) =>
        println(s"[LichessBot] [Game $gameId] Failed to send chat message '$message': $err")

  private def broadcastGameState(
      gameId: String,
      moves: String,
      isWhite: Boolean,
      wtime: Option[Long],
      btime: Option[Long]
  ): Unit =
    val timestamp = System.currentTimeMillis()
    val cache = GameStateCache(moves, isWhite, wtime, btime, timestamp)
    activeGames.put(gameId, cache)

    val botColor = if isWhite then "white" else "black"
    val json = ujson.Obj(
      "gameId" -> gameId,
      "moves" -> moves,
      "botColor" -> botColor,
      "wtime" -> wtime.map(ujson.Num(_)).getOrElse(ujson.Null),
      "btime" -> btime.map(ujson.Num(_)).getOrElse(ujson.Null),
      "lastUpdated" -> timestamp
    )
    wsServer.broadcast(ujson.write(json))

  private def maybeSendGreeting(gameId: String, moves: String): Unit =
    val movesCount = moves.trim.split(" ").filter(_.nonEmpty).length
    if movesCount <= 1 && !greetedGames.containsKey(gameId) then
      greetedGames.put(gameId, true)
      sendChatMessage(gameId, "Hallo und viel Glueck!")

  private def handleTerminalUx(gameId: String, status: String): Unit =
    if isTerminalStatus(status) && !finishedGames.containsKey(gameId) then
      finishedGames.put(gameId, true)
      sendChatMessage(gameId, "Gut gespielt!")
      greetedGames.remove(gameId)

  private def opponentActorId(gameFull: Value, botId: String, isWhite: Boolean): String =
    val side = if isWhite then "black" else "white"
    gameFull.obj.get(side).flatMap(_.obj.get("id")).map(_.str.toLowerCase).getOrElse(s"opponent-$botId")

  private def clockTimes(json: Value): (Option[Long], Option[Long]) =
    val wtime = json.obj.get("wtime").flatMap {
      case ujson.Num(num) => Some(num.toLong)
      case _              => None
    }
    val btime = json.obj.get("btime").flatMap {
      case ujson.Num(num) => Some(num.toLong)
      case _              => None
    }
    (wtime, btime)

  private def cumulativeMoves(json: Value): String =
    json.obj.get("moves").flatMap(_.strOpt).getOrElse("")

  private def statusValue(json: Value): String =
    json.obj.get("status").flatMap(_.strOpt).getOrElse("started")

  private def isTerminalStatus(status: String): Boolean =
    status != "started" && status != "created"

  private def logGameServiceError(
      gameId: String,
      operation: String,
      error: GameServiceClientError
  ): Unit =
    error match
      case GameServiceClientError.Auth(status, message) =>
        println(
          s"[LichessBot] [Game $gameId] Game Service auth/config error during $operation (HTTP $status): $message. Refusing to move."
        )
      case GameServiceClientError.Rejected(status, message) =>
        println(
          s"[LichessBot] [Game $gameId] Game Service rejected $operation (HTTP $status): $message. Refusing to move."
        )
      case GameServiceClientError.Unavailable(message) =>
        println(
          s"[LichessBot] [Game $gameId] Game Service unavailable during $operation: $message. Refusing local fallback to avoid split-brain state."
        )
      case GameServiceClientError.Unexpected(status, message) =>
        println(
          s"[LichessBot] [Game $gameId] Unexpected Game Service response during $operation (HTTP $status): $message. Refusing to move."
        )
      case GameServiceClientError.Decode(message) =>
        println(
          s"[LichessBot] [Game $gameId] Could not decode Game Service response during $operation: $message. Refusing to move."
        )

object LichessBotMain:
  private val DefaultToken = "YOUR_LICHESS"

  def main(args: Array[String]): Unit =
    implicit val ec: ExecutionContext = ExecutionContext.global
    val token = Option(System.getenv("LICHESS_BOT_TOKEN")).filter(_.nonEmpty).getOrElse(DefaultToken)
    val acceptIncomingChallenges =
      Option(System.getenv("LICHESS_BOT_ACCEPT_INCOMING_CHALLENGES"))
        .exists(value => Set("true", "1", "yes").contains(value.trim.toLowerCase))
    val challengePort = Option(System.getenv("CHALLENGE_HTTP_PORT")).flatMap(_.toIntOption).getOrElse(9324)
    val shutdownChallenge =
      Option(System.getenv("EXTERNAL_GAME_BOT_API_KEY")).map(_.trim).filter(_.nonEmpty) match
        case Some(apiKey) =>
          println(s"[LichessBot] Starting internal challenge API on port $challengePort...")
          Some(LichessChallengeServer.start(challengePort, apiKey, HttpLichessChallengeClient(token)))
        case None =>
          println("[LichessBot] Internal challenge API disabled: EXTERNAL_GAME_BOT_API_KEY is not configured.")
          None
    val bot = LichessBot(token, acceptIncomingChallenges = acceptIncomingChallenges)

    try bot.start()
    catch
      case NonFatal(e) =>
        println(s"[LichessBot] Critical error on startup: ${e.getMessage}")
        e.printStackTrace()
    finally shutdownChallenge.foreach(_.apply())
