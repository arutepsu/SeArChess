package chess.adapter.http4s.route

import chess.application.bot.BotChallengeColor

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.control.NonFatal

final case class LichessBotChallengeRequest(
    lichessUsername: String,
    clockLimitSeconds: Int,
    clockIncrementSeconds: Int,
    color: BotChallengeColor,
    rated: Boolean
)

final case class LichessBotChallengeResponse(
    challengeId: String,
    url: Option[String],
    status: String
)

trait LichessBotChallengeClient:
  def createChallenge(
      request: LichessBotChallengeRequest
  ): Either[LichessBotChallengeClientError, LichessBotChallengeResponse]

enum LichessBotChallengeClientError:
  case Rejected(status: Int, message: String)
  case Unavailable(message: String)
  case InvalidResponse(message: String)

final class HttpLichessBotChallengeClient(
    baseUrl: String,
    apiKey: String,
    timeoutMillis: Int = 5000,
    client: HttpClient = HttpClient.newHttpClient()
) extends LichessBotChallengeClient:

  override def createChallenge(
      request: LichessBotChallengeRequest
  ): Either[LichessBotChallengeClientError, LichessBotChallengeResponse] =
    try
      val httpRequest = HttpRequest
        .newBuilder(URI.create(s"${baseUrl.stripSuffix("/")}/challenge"))
        .timeout(Duration.ofMillis(timeoutMillis.toLong))
        .header("Content-Type", "application/json")
        .header("X-Bot-Api-Key", apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(ujson.write(toJson(request))))
        .build()
      val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
      response.statusCode() match
        case 200 | 201 => parseResponse(response.body())
        case status    =>
          Left(LichessBotChallengeClientError.Rejected(status, errorMessage(response.body(), status)))
    catch case NonFatal(e) =>
      Left(LichessBotChallengeClientError.Unavailable(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))

  private def toJson(request: LichessBotChallengeRequest): ujson.Value =
    ujson.Obj(
      "lichessUsername" -> request.lichessUsername,
      "clockLimitSeconds" -> request.clockLimitSeconds,
      "clockIncrementSeconds" -> request.clockIncrementSeconds,
      "color" -> request.color.toString.toLowerCase,
      "rated" -> request.rated
    )

  private def parseResponse(body: String): Either[LichessBotChallengeClientError, LichessBotChallengeResponse] =
    try
      val json = ujson.read(body)
      Right(
        LichessBotChallengeResponse(
          challengeId = json("challengeId").str,
          url = json.obj.get("url").flatMap {
            case ujson.Null => None
            case value      => Some(value.str)
          },
          status = json.obj.get("status").flatMap(_.strOpt).getOrElse("created")
        )
      )
    catch case NonFatal(e) =>
      Left(LichessBotChallengeClientError.InvalidResponse(e.getMessage))

  private def errorMessage(body: String, status: Int): String =
    try ujson.read(body).obj.get("message").flatMap(_.strOpt).getOrElse(s"lichess-bot returned HTTP $status")
    catch case NonFatal(_) => if body.trim.nonEmpty then body.trim else s"lichess-bot returned HTTP $status"
