package chess.lichessbot

import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

final case class GameServiceClientConfig(
    baseUrl: String,
    apiKey: String,
    platform: String,
    actorId: String
)

object GameServiceClientConfig:
  def fromEnv(env: String => Option[String] = key => Option(System.getenv(key))): Option[GameServiceClientConfig] =
    env("EXTERNAL_GAME_BOT_API_KEY").map(_.trim).filter(_.nonEmpty).map { apiKey =>
      GameServiceClientConfig(
        baseUrl = env("GAME_SERVICE_BASE_URL").map(_.trim).filter(_.nonEmpty).getOrElse("http://game-service:8080"),
        apiKey = apiKey,
        platform = env("EXTERNAL_GAME_BOT_PLATFORM").map(_.trim).filter(_.nonEmpty).getOrElse("Lichess"),
        actorId = env("EXTERNAL_GAME_BOT_ACTOR_ID").map(_.trim).filter(_.nonEmpty).getOrElse("searchess-bot")
      )
    }

enum NextAction:
  case AwaitExternalMove
  case TriggerAI
  case GameFinished

final case class ExternalGameResponse(
    platform: String,
    externalGameId: String,
    ourActorId: String,
    status: String,
    lastProcessedPly: Int
)

final case class IngestMovesResponse(
    lastProcessedPly: Int,
    gameStatus: String,
    nextAction: NextAction
)

final case class AiMoveResponse(
    uciMove: String,
    lastProcessedPly: Int,
    gameStatus: String
)

enum GameServiceClientError:
  case Auth(status: Int, message: String)
  case Rejected(status: Int, message: String)
  case Unavailable(message: String)
  case Unexpected(status: Int, message: String)
  case Decode(message: String)

class GameServiceClient(val config: GameServiceClientConfig, httpClient: HttpClient = HttpClient.newHttpClient()):

  def startExternalGame(
      externalGameId: String,
      ourColor: String,
      opponentActorId: String,
      mode: String = "AiVsExternal"
  ): Either[GameServiceClientError, ExternalGameResponse] =
    val body = ujson.Obj(
      "platform" -> config.platform,
      "externalGameId" -> externalGameId,
      "mode" -> mode,
      "ourColor" -> ourColor,
      "opponentActorId" -> opponentActorId
    )
    sendJson("POST", "/external-games", body).flatMap(parseExternalGameResponse)

  def getExternalGame(
      platform: String,
      externalGameId: String
  ): Either[GameServiceClientError, ExternalGameResponse] =
    sendJson("GET", externalGamePath(platform, externalGameId), ujson.Obj())
      .flatMap(parseExternalGameResponse)

  def ingestMoves(
      platform: String,
      externalGameId: String,
      cumulativeUciMoves: String
  ): Either[GameServiceClientError, IngestMovesResponse] =
    val body = ujson.Obj("uciMoves" -> cumulativeUciMoves)
    sendJson("POST", s"${externalGamePath(platform, externalGameId)}/moves", body)
      .flatMap(parseIngestMovesResponse)

  def requestAiMove(
      platform: String,
      externalGameId: String
  ): Either[GameServiceClientError, AiMoveResponse] =
    sendJson("POST", s"${externalGamePath(platform, externalGameId)}/ai-move", ujson.Obj())
      .flatMap(parseAiMoveResponse)

  def finishExternalGame(
      platform: String,
      externalGameId: String,
      reason: Option[String]
  ): Either[GameServiceClientError, ExternalGameResponse] =
    val body = reason.fold(ujson.Obj())(r => ujson.Obj("reason" -> r))
    sendJson("POST", s"${externalGamePath(platform, externalGameId)}/finish", body)
      .flatMap(parseExternalGameResponse)

  private def sendJson(
      method: String,
      path: String,
      body: ujson.Value
  ): Either[GameServiceClientError, String] =
    val builder = HttpRequest
      .newBuilder()
      .uri(URI.create(s"${config.baseUrl.stripSuffix("/")}$path"))
      .header("X-Bot-Api-Key", config.apiKey)
      .header("Content-Type", "application/json")

    val request =
      if method == "GET" then builder.GET().build()
      else builder.method(method, HttpRequest.BodyPublishers.ofString(ujson.write(body))).build()

    try
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
      response.statusCode() match
        case status if status >= 200 && status < 300 =>
          Right(response.body())
        case 401 | 403 =>
          Left(GameServiceClientError.Auth(response.statusCode(), errorMessage(response.body())))
        case 409 | 422 =>
          Left(GameServiceClientError.Rejected(response.statusCode(), errorMessage(response.body())))
        case 503 =>
          Left(GameServiceClientError.Unavailable(errorMessage(response.body())))
        case status =>
          Left(GameServiceClientError.Unexpected(status, errorMessage(response.body())))
    catch
      case NonFatal(e) =>
        Left(GameServiceClientError.Unavailable(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))

  private def parseExternalGameResponse(body: String): Either[GameServiceClientError, ExternalGameResponse] =
    decode(body) { json =>
      ExternalGameResponse(
        platform = json("platform").str,
        externalGameId = json("externalGameId").str,
        ourActorId = json("ourActorId").str,
        status = json("status").str,
        lastProcessedPly = json("lastProcessedPly").num.toInt
      )
    }

  private def parseIngestMovesResponse(body: String): Either[GameServiceClientError, IngestMovesResponse] =
    decode(body) { json =>
      IngestMovesResponse(
        lastProcessedPly = json("lastProcessedPly").num.toInt,
        gameStatus = json("gameStatus").str,
        nextAction = parseNextAction(json("nextAction").str)
      )
    }

  private def parseAiMoveResponse(body: String): Either[GameServiceClientError, AiMoveResponse] =
    decode(body) { json =>
      AiMoveResponse(
        uciMove = json("uciMove").str,
        lastProcessedPly = json("lastProcessedPly").num.toInt,
        gameStatus = json("gameStatus").str
      )
    }

  private def decode[A](body: String)(read: ujson.Value => A): Either[GameServiceClientError, A] =
    try Right(read(ujson.read(body)))
    catch case NonFatal(e) => Left(GameServiceClientError.Decode(Option(e.getMessage).getOrElse("Invalid Game Service JSON response")))

  private def parseNextAction(value: String): NextAction =
    value match
      case "AwaitExternalMove" => NextAction.AwaitExternalMove
      case "TriggerAI"         => NextAction.TriggerAI
      case "GameFinished"      => NextAction.GameFinished
      case other               => throw IllegalArgumentException(s"Unknown nextAction: $other")

  private def externalGamePath(platform: String, externalGameId: String): String =
    s"/external-games/${encode(platform)}/${encode(externalGameId)}"

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

  private def errorMessage(body: String): String =
    try ujson.read(body)("message").str
    catch case NonFatal(_) => body
