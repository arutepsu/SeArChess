package chess.lichessbot

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.{Host, Port}
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

final case class ChallengeCommand(
    lichessUsername: String,
    clockLimitSeconds: Int,
    clockIncrementSeconds: Int,
    color: String,
    rated: Boolean,
    variant: String = "standard"
)

final case class ChallengeCreated(challengeId: String, url: Option[String], status: String)

trait LichessChallengeClient:
  def create(command: ChallengeCommand): Either[LichessChallengeError, ChallengeCreated]

enum LichessChallengeError:
  case Rejected(status: Int, message: String)
  case Unavailable(message: String)
  case InvalidResponse(message: String)

final class HttpLichessChallengeClient(
    token: String,
    client: HttpClient = HttpClient.newHttpClient()
) extends LichessChallengeClient:

  override def create(command: ChallengeCommand): Either[LichessChallengeError, ChallengeCreated] =
    try
      val request = HttpRequest
        .newBuilder(URI.create(s"https://lichess.org/api/challenge/${encode(command.lichessUsername)}"))
        .header("Authorization", s"Bearer $token")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(formBody(command)))
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() match
        case 200 | 201 => parseResponse(response.body())
        case status    => Left(LichessChallengeError.Rejected(status, errorMessage(response.body(), status)))
    catch case NonFatal(e) =>
      Left(LichessChallengeError.Unavailable(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))

  private[lichessbot] def formBody(command: ChallengeCommand): String =
    Map(
      "rated" -> command.rated.toString,
      "clock.limit" -> command.clockLimitSeconds.toString,
      "clock.increment" -> command.clockIncrementSeconds.toString,
      "color" -> command.color,
      "variant" -> command.variant
    ).map((k, v) => s"${encode(k)}=${encode(v)}").mkString("&")

  private def parseResponse(body: String): Either[LichessChallengeError, ChallengeCreated] =
    try
      val json = ujson.read(body)
      val id = json.obj.get("challenge").flatMap(_.obj.get("id")).flatMap(_.strOpt)
        .orElse(json.obj.get("id").flatMap(_.strOpt))
        .getOrElse("")
      if id.isEmpty then Left(LichessChallengeError.InvalidResponse("Lichess response did not include a challenge id"))
      else
        Right(
          ChallengeCreated(
            challengeId = id,
            url = json.obj.get("challenge").flatMap(_.obj.get("url")).flatMap(_.strOpt)
              .orElse(json.obj.get("url").flatMap(_.strOpt)),
            status = json.obj.get("challenge").flatMap(_.obj.get("status")).flatMap(_.strOpt)
              .orElse(json.obj.get("status").flatMap(_.strOpt))
              .getOrElse("created")
          )
        )
    catch case NonFatal(e) => Left(LichessChallengeError.InvalidResponse(e.getMessage))

  private def errorMessage(body: String, status: Int): String =
    try ujson.read(body).obj.get("error").flatMap(_.strOpt).getOrElse(s"Lichess returned HTTP $status")
    catch case NonFatal(_) => if body.trim.nonEmpty then body.trim else s"Lichess returned HTTP $status"

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

final class LichessChallengeRoutes(apiKey: String, client: LichessChallengeClient):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case req @ POST -> Root / "challenge" =>
    req.headers.get(CIString("X-Bot-Api-Key")).map(_.head.value) match
      case Some(value) if constantTimeEquals(value, apiKey) =>
        req.bodyText.compile.string.flatMap { body =>
          parseCommand(body) match
            case Left(message) => json(Status.UnprocessableEntity, ujson.Obj("code" -> "INVALID_CHALLENGE", "message" -> message))
            case Right(command) =>
              client.create(command) match
                case Right(created) =>
                  json(
                    Status.Created,
                    ujson.Obj(
                      "challengeId" -> created.challengeId,
                      "url" -> created.url.map(ujson.Str(_)).getOrElse(ujson.Null),
                      "status" -> created.status
                    )
                  )
                case Left(LichessChallengeError.Rejected(status, message)) =>
                  json(Status.BadGateway, ujson.Obj("code" -> "LICHESS_REJECTED", "message" -> s"HTTP $status: $message"))
                case Left(LichessChallengeError.Unavailable(message)) =>
                  json(Status.ServiceUnavailable, ujson.Obj("code" -> "LICHESS_UNAVAILABLE", "message" -> message))
                case Left(LichessChallengeError.InvalidResponse(message)) =>
                  json(Status.BadGateway, ujson.Obj("code" -> "LICHESS_INVALID_RESPONSE", "message" -> message))
        }
      case _ =>
        json(Status.Unauthorized, ujson.Obj("code" -> "UNAUTHORIZED", "message" -> "Missing or invalid X-Bot-Api-Key"))
  }

  private def parseCommand(body: String): Either[String, ChallengeCommand] =
    try
      val json = ujson.read(body)
      val command = ChallengeCommand(
        lichessUsername = json("lichessUsername").str.trim,
        clockLimitSeconds = json("clockLimitSeconds").num.toInt,
        clockIncrementSeconds = json("clockIncrementSeconds").num.toInt,
        color = json.obj.get("color").flatMap(_.strOpt).getOrElse("random").trim.toLowerCase,
        rated = json.obj.get("rated").flatMap(_.boolOpt).getOrElse(false),
        variant = json.obj.get("variant").flatMap(_.strOpt).getOrElse("standard").trim.toLowerCase
      )
      validate(command)
    catch case NonFatal(_) => Left("Request body must be valid JSON")

  private def validate(command: ChallengeCommand): Either[String, ChallengeCommand] =
    for
      _ <- Either.cond(command.lichessUsername.nonEmpty, (), "lichessUsername is required")
      _ <- Either.cond(!command.rated, (), "Rated challenges are not allowed in Phase 3")
      _ <- Either.cond(command.variant == "standard", (), "Only standard variant is allowed")
      _ <- Either.cond(Set("white", "black", "random").contains(command.color), (), "color must be white, black, or random")
      _ <- Either.cond(command.clockLimitSeconds >= 60 && command.clockLimitSeconds <= 10800, (), "clockLimitSeconds must be between 60 and 10800")
      _ <- Either.cond(command.clockIncrementSeconds >= 0 && command.clockIncrementSeconds <= 60, (), "clockIncrementSeconds must be between 0 and 60")
    yield command

  private def json(status: Status, body: ujson.Value): IO[Response[IO]] =
    IO.pure(
      Response[IO](
        status = status,
        headers = Headers(`Content-Type`(MediaType.application.json)),
        body = Stream.emits(ujson.write(body).getBytes("UTF-8")).covary[IO]
      )
    )

  private def constantTimeEquals(a: String, b: String): Boolean =
    val max = math.max(a.length, b.length)
    var diff = a.length ^ b.length
    var i = 0
    while i < max do
      val ca = if i < a.length then a.charAt(i) else 0
      val cb = if i < b.length then b.charAt(i) else 0
      diff |= ca ^ cb
      i += 1
    diff == 0

object LichessChallengeServer:
  def start(portValue: Int, apiKey: String, client: LichessChallengeClient): () => Unit =
    val host = Host.fromString("0.0.0.0").getOrElse(throw RuntimeException("Invalid challenge host"))
    val port = Port.fromInt(portValue).getOrElse(throw RuntimeException(s"Invalid challenge port: $portValue"))
    val (_, shutdown) =
      EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(LichessChallengeRoutes(apiKey, client).routes.orNotFound)
        .build
        .allocated
        .unsafeRunSync()
    () => shutdown.unsafeRunSync()
