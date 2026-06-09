package chess.lichessbridge

import cats.effect.IO
import chess.lichessbridge.LichessError.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.control.NonFatal

/** Real Lichess HTTP API client.
  *
  * Uses java.net.http.HttpClient (JDK 11+, already available in this project's bot-service).
  * Connect timeout: 10 seconds. Read timeout (per-request): 15 seconds.
  *
  * Token is never logged or included in error messages.
  */
final class LichessHttpClient(
    baseUrl: String = "https://lichess.org",
    underlying: HttpClient = HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build()
) extends LichessClient[IO]:

  private val readTimeout = Duration.ofSeconds(15)

  def getBotProfile(token: String): IO[Either[LichessError, BotProfile]] =
    IO {
      val req = HttpRequest
        .newBuilder(URI.create(s"$baseUrl/api/account"))
        .timeout(readTimeout)
        .header("Authorization", s"Bearer $token")
        .header("Accept", "application/json")
        .GET()
        .build()
      executeProfileRequest(req)
    }.handleErrorWith { case NonFatal(e) =>
      IO.pure(Left(NetworkError(e.getMessage)))
    }

  def validateToken(token: String): IO[Either[LichessError, Boolean]] =
    getBotProfile(token).map(_.map(_ => true))

  def challengeAi(
      token: String,
      level: Int = 1,
      clockLimit: Int = 300,
      clockIncrement: Int = 0
  ): IO[Either[LichessError, ChallengeResult]] =
    IO {
      val body = s"level=$level&clock.limit=$clockLimit&clock.increment=$clockIncrement&color=random&rated=false"
      val req = HttpRequest
        .newBuilder(URI.create(s"$baseUrl/api/challenge/ai"))
        .timeout(readTimeout)
        .header("Authorization", s"Bearer $token")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      executeChallengeRequest(req)
    }.handleErrorWith { case NonFatal(e) =>
      IO.pure(Left(NetworkError(e.getMessage)))
    }

  def acceptChallenge(token: String, challengeId: String): IO[Either[LichessError, Unit]] =
    IO.blocking {
      val req = HttpRequest
        .newBuilder(URI.create(s"$baseUrl/api/challenge/$challengeId/accept"))
        .timeout(readTimeout)
        .header("Authorization", s"Bearer $token")
        .POST(HttpRequest.BodyPublishers.noBody())
        .build()
      val resp = underlying.send(req, HttpResponse.BodyHandlers.ofString())
      resp.statusCode() match
        case 200       => Right(())
        case 401 | 403 => Left(Unauthorized("Token rejected by Lichess."))
        case 429       => Left(RateLimited(retryAfterHeader(resp)))
        case status    => Left(UnexpectedResponse(status, resp.body().take(300)))
    }.handleErrorWith { case NonFatal(e) =>
      IO.pure(Left(NetworkError(e.getMessage)))
    }

  def declineChallenge(token: String, challengeId: String, reason: String): IO[Either[LichessError, Unit]] =
    IO.blocking {
      val body = s"reason=$reason"
      val req = HttpRequest
        .newBuilder(URI.create(s"$baseUrl/api/challenge/$challengeId/decline"))
        .timeout(readTimeout)
        .header("Authorization", s"Bearer $token")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val resp = underlying.send(req, HttpResponse.BodyHandlers.ofString())
      resp.statusCode() match
        case 200       => Right(())
        case 401 | 403 => Left(Unauthorized("Token rejected by Lichess."))
        case 429       => Left(RateLimited(retryAfterHeader(resp)))
        case status    => Left(UnexpectedResponse(status, resp.body().take(300)))
    }.handleErrorWith { case NonFatal(e) =>
      IO.pure(Left(NetworkError(e.getMessage)))
    }

  def submitMove(token: String, gameId: String, move: String): IO[Either[LichessError, Unit]] =
    IO.blocking {
      val req = HttpRequest
        .newBuilder(URI.create(s"$baseUrl/api/bot/game/$gameId/move/$move"))
        .timeout(readTimeout)
        .header("Authorization", s"Bearer $token")
        .POST(HttpRequest.BodyPublishers.noBody())
        .build()
      val resp = underlying.send(req, HttpResponse.BodyHandlers.ofString())
      resp.statusCode() match
        case 200       => Right(())
        case 400       => Left(UnexpectedResponse(400, "Move rejected: illegal move or game already finished."))
        case 401 | 403 => Left(Unauthorized("Token rejected by Lichess."))
        case 429       => Left(RateLimited(retryAfterHeader(resp)))
        case status    => Left(UnexpectedResponse(status, resp.body().take(300)))
    }.handleErrorWith { case NonFatal(e) =>
      IO.pure(Left(NetworkError(e.getMessage)))
    }

  // ── Private helpers ────────────────────────────────────────────────────────

  private def executeProfileRequest(req: HttpRequest): Either[LichessError, BotProfile] =
    val resp = underlying.send(req, HttpResponse.BodyHandlers.ofString())
    resp.statusCode() match
      case 200       => LichessHttpClient.parseProfile(resp.body())
      case 401 | 403 => Left(Unauthorized("Token rejected by Lichess."))
      case 429       => Left(RateLimited(retryAfterHeader(resp)))
      case status    => Left(UnexpectedResponse(status, resp.body().take(300)))

  private def executeChallengeRequest(req: HttpRequest): Either[LichessError, ChallengeResult] =
    val resp = underlying.send(req, HttpResponse.BodyHandlers.ofString())
    resp.statusCode() match
      case s if s == 200 || s == 201 => LichessHttpClient.parseChallenge(resp.body())
      case 401 | 403                 => Left(Unauthorized("Token rejected by Lichess."))
      case 429                       => Left(RateLimited(retryAfterHeader(resp)))
      case status                    => Left(UnexpectedResponse(status, resp.body().take(300)))

  /** Extract the Retry-After header value as an Int if present. */
  private def retryAfterHeader(resp: HttpResponse[String]): Option[Int] =
    val opt = resp.headers().firstValue("Retry-After")
    if opt.isPresent then opt.get().toIntOption else None

/** Pure JSON parsing helpers, exposed so tests can exercise them without a real HTTP stack. */
object LichessHttpClient:

  def parseProfile(body: String): Either[LichessError, BotProfile] =
    scala.util.Try {
      val json  = ujson.read(body)
      val id    = json("id").str
      val uname = json("username").str
      val title = json.obj.get("title").flatMap(_.strOpt)
      val isBot = title.exists(_.toUpperCase == "BOT")
      BotProfile(id, uname, title, isBot)
    }.toEither.left.map(e => UnexpectedResponse(200, s"JSON parse error: ${e.getMessage}"))

  def parseChallenge(body: String): Either[LichessError, ChallengeResult] =
    scala.util.Try {
      val json = ujson.read(body)
      // The challenge/ai response may nest data under "challenge" or directly at root.
      val gameId = json.obj.get("id").flatMap(_.strOpt)
        .orElse(json.obj.get("challenge").flatMap(_.obj.get("id")).flatMap(_.strOpt))
      val gameUrl = json.obj.get("url").flatMap(_.strOpt)
        .orElse(json.obj.get("challenge").flatMap(_.obj.get("url")).flatMap(_.strOpt))
      (gameId, gameUrl) match
        case (Some(id), Some(url)) => ChallengeResult(id, url)
        case (Some(id), None)      => ChallengeResult(id, s"https://lichess.org/$id")
        case _                     => sys.error("missing game id in challenge response")
    }.toEither.left.map(e => UnexpectedResponse(200, s"JSON parse error: ${e.getMessage}"))

  /** Map an HTTP status + optional Retry-After value to a LichessError for 4xx/5xx. */
  def mapStatus(status: Int, body: String, retryAfterSeconds: Option[Int]): Either[LichessError, Nothing] =
    status match
      case 401 | 403 => Left(Unauthorized("Token rejected by Lichess."))
      case 429       => Left(RateLimited(retryAfterSeconds))
      case _         => Left(UnexpectedResponse(status, body.take(300)))
