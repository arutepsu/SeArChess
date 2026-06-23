package chess.tournamentservice

import cats.effect.IO
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.control.NonFatal

final case class RunnerParticipant(
  tournamentServerBotId:   String,
  tournamentServerBotName: String,
  searchessCatalogBotId:   Option[String]
)

trait SearchessParticipantClient:
  def fetchParticipants(tournamentId: String): IO[Either[String, List[RunnerParticipant]]]

final class JdkSearchessParticipantClient(
    userServiceBaseUrl: String,
    underlying: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
) extends SearchessParticipantClient:

  private val base = userServiceBaseUrl.stripSuffix("/")

  def fetchParticipants(tournamentId: String): IO[Either[String, List[RunnerParticipant]]] =
    IO.blocking {
      val url = s"$base/users/tournaments/$tournamentId/participants"
      val req = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("Accept", "application/json")
        .GET()
        .build()
      underlying.send(req, HttpResponse.BodyHandlers.ofString())
    }.map { resp =>
      resp.statusCode() match
        case 200 =>
          try
            val json = ujson.read(resp.body())
            val participants = json("participants").arr.toList.flatMap { p =>
              for
                botId   <- p.obj.get("tournamentServerBotId").flatMap(_.strOpt)
                botName <- p.obj.get("tournamentServerBotName").flatMap(_.strOpt)
              yield RunnerParticipant(
                tournamentServerBotId   = botId,
                tournamentServerBotName = botName,
                searchessCatalogBotId   = p.obj.get("searchessCatalogBotId").flatMap(_.strOpt)
              )
            }
            Right(participants)
          catch case NonFatal(e) => Left(s"Failed to parse participants: ${Option(e.getMessage).getOrElse("parse error")}")
        case n =>
          Left(s"HTTP $n from user-service participants: ${resp.body().take(200)}")
    }.handleError:
      case NonFatal(e) =>
        Left(s"Network error fetching participants: ${Option(e.getMessage).getOrElse("unknown")}")
