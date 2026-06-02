package chess.adapter.http4s.route

import cats.effect.IO
import chess.adapter.http4s.route.Http4sRouteSupport.*
import org.http4s.*
import org.http4s.dsl.io.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.http.HttpRequest.BodyPublishers
import scala.util.control.NonFatal

class Http4sBotRoutes:
  private val httpClient = HttpClient.newHttpClient()
  private val botUrl = Option(System.getenv("LICHESS_BOT_URL")).getOrElse("http://localhost:9324")

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "bot" / "status" =>
      proxyToBot("GET", "/api/bot/status")

    case POST -> Root / "bot" / "start" =>
      proxyToBot("POST", "/api/bot/activate")

    case POST -> Root / "bot" / "stop" =>
      proxyToBot("POST", "/api/bot/deactivate")
  }

  private def proxyToBot(method: String, path: String): IO[Response[IO]] =
    IO.interruptible {
      val targetUri = botUrl.stripSuffix("/") + path
      val requestBuilder = HttpRequest.newBuilder()
        .uri(URI.create(targetUri))

      val request = if (method == "POST") {
        requestBuilder.POST(BodyPublishers.noBody()).build()
      } else {
        requestBuilder.GET().build()
      }

      try {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val statusCode = Status.fromInt(response.statusCode()).getOrElse(Status.InternalServerError)
        val responseBody = response.body()
        val parsedJson = ujson.read(responseBody)
        (statusCode, parsedJson)
      } catch {
        case NonFatal(e) =>
          // If the bot container is down or unreachable, return stopped status with logs
          (Status.Ok, ujson.Obj(
            "status" -> "stopped",
            "logs" -> ujson.Arr(s"Lichess Bot is not reachable at $botUrl: ${e.getMessage}")
          ))
      }
    }.flatMap { case (status, json) =>
      jsonResponse(status, json)
    }
