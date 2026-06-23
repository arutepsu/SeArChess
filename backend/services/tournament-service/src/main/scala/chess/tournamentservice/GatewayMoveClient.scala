package chess.tournamentservice

import cats.effect.IO
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.control.NonFatal

trait GatewayMoveClient:
  def submitMove(
    tournamentId:            String,
    gameId:                  String,
    tournamentServerBotId:   String,
    tournamentServerBotName: String,
    uciMove:                 String
  ): IO[Either[String, Unit]]

final class JdkGatewayMoveClient(
    gatewayBaseUrl: String,
    serviceToken:   Option[String] = None,
    underlying: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
) extends GatewayMoveClient:

  private val base = gatewayBaseUrl.stripSuffix("/")

  def submitMove(
    tournamentId:            String,
    gameId:                  String,
    tournamentServerBotId:   String,
    tournamentServerBotName: String,
    uciMove:                 String
  ): IO[Either[String, Unit]] =
    IO.blocking {
      val url  = s"$base/api/internal/tournament/$tournamentId/game/$gameId/move/$uciMove"
      val body = ujson.write(ujson.Obj(
        "tournamentServerBotId"   -> tournamentServerBotId,
        "tournamentServerBotName" -> tournamentServerBotName
      ))
      val builder = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json")
      val authedBuilder = serviceToken.fold(builder)(t => builder.header("Authorization", s"Bearer $t"))
      val req = authedBuilder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
      underlying.send(req, HttpResponse.BodyHandlers.ofString())
    }.map { resp =>
      resp.statusCode() match
        case 200 => Right(())
        case n   => Left(s"Gateway rejected move '$uciMove': HTTP $n ${resp.body().take(200)}")
    }.handleError:
      case NonFatal(e) =>
        Left(s"Network error submitting move '$uciMove': ${Option(e.getMessage).getOrElse("unknown")}")
