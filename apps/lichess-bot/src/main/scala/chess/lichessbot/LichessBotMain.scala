package chess.lichessbot

import sttp.client3._
import ujson.Value
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import java.io.{BufferedReader, InputStreamReader, InputStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.ConcurrentHashMap
import scala.util.control.NonFatal
import chess.domain.model.{Move, PieceType, Position}
import chess.domain.rules.GameStateRules
import chess.domain.state.GameStateFactory

/** Lichess Bot client to connect to the Lichess API using sttp (for requests) and Java HttpClient
  * (for ND-JSON streaming) along with ujson for parsing.
  *
  * Designed in a functional style with tail-recursive streaming to respect strict WartRemover
  * settings.
  *
  * @param token
  *   The Lichess API Token.
  */
class LichessBot(val token: String)(implicit ec: ExecutionContext) {
  private val backend = HttpClientSyncBackend()
  private val httpClient = HttpClient.newHttpClient()

  // Cache to store the number of moves processed for each game to prevent double moves
  private val lastMovesCountMap = new ConcurrentHashMap[String, java.lang.Integer]()

  def start(): Unit = {
    println("[LichessBot] Loading account details...")
    val botId = fetchBotId()
    println(s"[LichessBot] Successfully logged in as bot ID: $botId")

    println("[LichessBot] Starting event stream listening...")
    connectToEventStream(botId)
  }

  /** Fetches the profile account data of the bot to dynamically determine its username/id.
    */
  private def fetchBotId(): String = {
    val request = basicRequest
      .get(uri"https://lichess.org/api/account")
      .header("Authorization", s"Bearer $token")

    val response = request.send(backend)
    response.body match {
      case Right(bodyStr) =>
        val json = ujson.read(bodyStr)
        json("id").str.toLowerCase
      case Left(error) =>
        // We print the error and terminate or throw. Throwing is acceptable as configuration guard.
        throw new RuntimeException(s"Failed to fetch Lichess account info: $error")
    }
  }

  /** Starts a persistent connection to the Lichess Event Stream.
    */
  private def connectToEventStream(botId: String): Unit = {
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create("https://lichess.org/api/stream/event"))
      .header("Authorization", s"Bearer $token")
      .GET()
      .build()

    try {
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
      if (response.statusCode() == 200) {
        val reader = new BufferedReader(new InputStreamReader(response.body(), "UTF-8"))
        try {
          readEventLines(reader, botId)
        } finally {
          reader.close()
          response.body().close()
        }
      } else {
        println(
          s"[LichessBot] Event Stream returned status ${response.statusCode()}. Reconnecting in 5 seconds..."
        )
        Thread.sleep(5000)
        connectToEventStream(botId)
      }
    } catch {
      case NonFatal(e) =>
        println(
          s"[LichessBot] Event Stream disconnected: ${e.getMessage}. Reconnecting in 5 seconds..."
        )
        Thread.sleep(5000)
        connectToEventStream(botId)
    }
  }

  /** Tail-recursively reads and processes incoming event lines to avoid mutable var and return
    * statements.
    */
  @scala.annotation.tailrec
  private def readEventLines(reader: BufferedReader, botId: String): Unit = {
    val line = reader.readLine()
    if (line != null) {
      val trimmed = line.trim
      // Keep-Alive Skip: Lichess streams send newlines (\n) to keep connection alive.
      // Parsing empty strings with ujson will crash, so we explicitly ignore empty lines.
      if (trimmed.nonEmpty) {
        try {
          handleEvent(trimmed, botId)
        } catch {
          case NonFatal(e) =>
            println(
              s"[LichessBot] [Event Error] Error handling event: $trimmed, error: ${e.getMessage}"
            )
        }
      }
      readEventLines(reader, botId)
    }
  }

  /** Dispatches incoming event lines.
    */
  private def handleEvent(line: String, botId: String): Unit = {
    val json = ujson.read(line)
    json("type").str match {
      case "challenge" =>
        val challengeId = json("challenge")("id").str
        val challenger = json("challenge")("challenger")("id").str
        println(s"[LichessBot] Received challenge $challengeId from $challenger. Accepting...")
        acceptChallenge(challengeId)

      case "gameStart" =>
        val gameId = json("game")("id").str
        println(s"[LichessBot] Game started: $gameId. Starting game stream...")
        val _ = Future {
          try {
            connectToGameStream(gameId, botId)
          } catch {
            case NonFatal(e) =>
              println(
                s"[LichessBot] [Game Stream Error] Game $gameId stream finished with error: ${e.getMessage}"
              )
          }
        }

      case other =>
        // Ignore other events like challengeCanceled, challengeDeclined, etc.
        println(s"[LichessBot] Ignored event type: $other")
    }
  }

  /** Accepts a challenge.
    */
  private def acceptChallenge(challengeId: String): Unit = {
    val request = basicRequest
      .post(uri"https://lichess.org/api/challenge/$challengeId/accept")
      .header("Authorization", s"Bearer $token")

    val response = request.send(backend)
    val _ = response.body match {
      case Right(_) =>
        println(s"[LichessBot] Successfully accepted challenge $challengeId")
      case Left(err) =>
        println(s"[LichessBot] Failed to accept challenge $challengeId: $err")
    }
  }

  /** Connects to a specific game's stream and processes move updates.
    */
  private def connectToGameStream(gameId: String, botId: String): Unit = {
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(s"https://lichess.org/api/bot/game/stream/$gameId"))
      .header("Authorization", s"Bearer $token")
      .GET()
      .build()

    try {
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
      if (response.statusCode() == 200) {
        val reader = new BufferedReader(new InputStreamReader(response.body(), "UTF-8"))
        try {
          readGameLines(reader, gameId, botId, isWhite = false)
        } finally {
          reader.close()
          response.body().close()
          val _ = lastMovesCountMap.remove(gameId)
          println(s"[LichessBot] [Game $gameId] Stream closed.")
        }
      } else {
        println(s"[LichessBot] [Game $gameId] Game Stream returned status ${response.statusCode()}")
      }
    } catch {
      case NonFatal(e) =>
        println(s"[LichessBot] [Game $gameId] Failed to connect to game stream: ${e.getMessage}")
    }
  }

  /** Tail-recursively reads and processes game updates to avoid mutable var and return statements.
    */
  @scala.annotation.tailrec
  private def readGameLines(
      reader: BufferedReader,
      gameId: String,
      botId: String,
      isWhite: Boolean
  ): Unit = {
    val line = reader.readLine()
    if (line != null) {
      val trimmed = line.trim
      // Keep-Alive Skip: Lichess streams send newlines (\n) to keep connection alive.
      val nextIsWhite = if (trimmed.nonEmpty) {
        try {
          val json = ujson.read(trimmed)
          json("type").str match {
            case "gameFull" =>
              val determinedColor = json("white")("id").str.toLowerCase == botId
              val moves = json("state")("moves").str
              val status = json("state")("status").str
              println(s"[LichessBot] [Game $gameId] Initialized. Bot Color: ${
                  if (determinedColor) "White" else "Black"
                }. Status: $status")
              processTurn(gameId, moves, determinedColor, status)
              determinedColor

            case "gameState" =>
              val moves = json("moves").str
              val status = json("status").str
              processTurn(gameId, moves, isWhite, status)
              isWhite

            case _ => // Ignore other events like chatLine
              isWhite
          }
        } catch {
          case NonFatal(e) =>
            println(
              s"[LichessBot] [Game $gameId] Error parsing game stream line: $trimmed, error: ${e.getMessage}"
            )
            isWhite
        }
      } else {
        isWhite
      }
      readGameLines(reader, gameId, botId, nextIsWhite)
    }
  }

  /** Checks if the game is active, calculates whose turn it is, and makes a move if it is the Bot's
    * turn.
    */
  private def processTurn(gameId: String, moves: String, isWhite: Boolean, status: String): Unit = {
    if (status == "started") {
      val movesList = moves.trim.split(" ").filter(_.nonEmpty)
      val movesCount = movesList.length

      // If we are White, we play on even move counts (0, 2, 4...). If we are Black, we play on odd move counts (1, 3, 5...).
      val isOurTurn = (isWhite && movesCount % 2 == 0) || (!isWhite && movesCount % 2 == 1)

      if (isOurTurn) {
        val prevCountOpt = Option(lastMovesCountMap.get(gameId))
        // Double-Move Protection: Only calculate and submit if we haven't already processed this move count
        // Used fold to avoid Option.get and respect WartRemover rules.
        val isNewMove = prevCountOpt.fold(true)(prevCount => movesCount > prevCount.intValue())
        if (isNewMove) {
          val _ = lastMovesCountMap.put(gameId, movesCount)
          println(
            s"[LichessBot] [Game $gameId] Bot turn detected! (Move count: $movesCount). Calculating move..."
          )

          try {
            val nextMove = calculateNextMove(moves)
            println(s"[LichessBot] [Game $gameId] Calculated move: '$nextMove'. Submitting...")
            submitMove(gameId, nextMove)
          } catch {
            case NonFatal(e) =>
              println(
                s"[LichessBot] [Game $gameId] Failed to calculate or submit move: ${e.getMessage}"
              )
          }
        } else {
          println(
            s"[LichessBot] [Game $gameId] Skip move calculation (Move count $movesCount already processed to prevent double moves)."
          )
        }
      }
    } else {
      println(
        s"[LichessBot] [Game $gameId] Game is not active (Status: $status). Skipping turn processing."
      )
    }
  }

  /** Submits the calculated move to the Lichess API.
    */
  private def submitMove(gameId: String, move: String): Unit = {
    val request = basicRequest
      .post(uri"https://lichess.org/api/bot/game/$gameId/move/$move")
      .header("Authorization", s"Bearer $token")

    val response = request.send(backend)
    val _ = response.body match {
      case Right(_) =>
        println(s"[LichessBot] [Game $gameId] Move $move successfully submitted")
      case Left(err) =>
        println(s"[LichessBot] [Game $gameId] Failed to submit move $move: $err")
    }
  }

  /** Schnittstelle zur Schachlogik des Benutzers.
    *
    * @param moves
    *   Eine Leerzeichen-separierte Liste aller bisherigen Züge, z.B. "e2e4 e7e5"
    * @return
    *   Der berechnete nächste Zug in UCI-Notation (z.B. "g1f3")
    */
  def calculateNextMove(moves: String): String = {
    println(s"[LichessBot] calculateNextMove received moves history: '$moves'")

    val currentState = replayMoves(moves)
    val legalMoves = GameStateRules.legalMoves(currentState).toVector.sortBy(moveToUci)

    legalMoves.headOption match {
      case Some(move) => moveToUci(move)
      case None => throw new RuntimeException("No legal moves available for the current game state")
    }
  }

  private def replayMoves(moves: String): chess.domain.state.GameState = {
    moves
      .split(" ")
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .foldLeft(GameStateFactory.initial()) { (state, moveText) =>
        val move = parseUciMove(moveText)
        GameStateRules.applyMove(state, move) match {
          case Right(nextState) => nextState
          case Left(error) =>
            throw new RuntimeException(s"Failed to replay move '$moveText': $error")
        }
      }
  }

  private def parseUciMove(moveText: String): Move = {
    if (moveText.length < 4) {
      throw new RuntimeException(s"Invalid UCI move: '$moveText'")
    }

    val from = Position
      .fromAlgebraic(moveText.substring(0, 2))
      .fold(
        error => throw new RuntimeException(s"Invalid UCI source square in '$moveText': $error"),
        identity
      )
    val to = Position
      .fromAlgebraic(moveText.substring(2, 4))
      .fold(
        error => throw new RuntimeException(s"Invalid UCI target square in '$moveText': $error"),
        identity
      )
    val promotion =
      if moveText.length == 5 then
        moveText.charAt(4).toLower match
          case 'q' => Some(PieceType.Queen)
          case 'r' => Some(PieceType.Rook)
          case 'b' => Some(PieceType.Bishop)
          case 'n' => Some(PieceType.Knight)
          case other =>
            throw new RuntimeException(s"Invalid UCI promotion piece '$other' in '$moveText'")
      else None

    Move(from, to, promotion)
  }

  private def moveToUci(move: Move): String = {
    val base = s"${move.from}${move.to}"
    move.promotion match {
      case Some(PieceType.Queen)                              => base + "q"
      case Some(PieceType.Rook)                               => base + "r"
      case Some(PieceType.Bishop)                             => base + "b"
      case Some(PieceType.Knight)                             => base + "n"
      case Some(PieceType.King) | Some(PieceType.Pawn) | None => base
    }
  }
}

object LichessBotMain {
  // Hinterlegen Sie hier Ihren Lichess-API-Token
  val token = "YOUR_LICHESS"

  def main(args: Array[String]): Unit = {
    implicit val ec: ExecutionContext = ExecutionContext.global
    val bot = new LichessBot(token)

    try {
      bot.start()
    } catch {
      case NonFatal(e) =>
        println(s"[LichessBot] Critical error on startup: ${e.getMessage}")
        e.printStackTrace()
    }
  }
}
