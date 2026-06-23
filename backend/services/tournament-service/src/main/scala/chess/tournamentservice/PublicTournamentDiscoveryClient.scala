package chess.tournamentservice

import cats.effect.IO
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.control.NonFatal

// Discovers runnable public tournaments from the external Tournament Server.
// "Runnable" means the tournament is in the "started" bucket of the list response.
// The Tournament Server list endpoint returns { created: [...], started: [...], finished: [...] }.
// This client is called by the scheduler to determine which tournament IDs to tick.
// It calls the Tournament Server DIRECTLY (no Keycloak auth required for public reads).
trait PublicTournamentDiscoveryClient:
  def listRunnableTournamentIds(): IO[Either[String, List[String]]]

final class JdkPublicTournamentDiscoveryClient(
    tournamentServerBaseUrl: String,
    underlying: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
) extends PublicTournamentDiscoveryClient:

  private val base = tournamentServerBaseUrl.stripSuffix("/")

  def listRunnableTournamentIds(): IO[Either[String, List[String]]] =
    IO.blocking {
      val url = s"$base/api/tournament"
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
            val ids  = json.obj.get("started").toList.flatMap { arr =>
              arr.arr.flatMap(entry => entry.obj.get("id").flatMap(_.strOpt)).toList
            }
            Right(ids)
          catch case NonFatal(e) =>
            Left(s"Failed to parse tournament list: ${Option(e.getMessage).getOrElse("parse error")}")
        case n =>
          Left(s"HTTP $n from tournament server list: ${resp.body().take(200)}")
    }.handleError:
      case NonFatal(e) =>
        Left(s"Network error fetching tournament list: ${Option(e.getMessage).getOrElse("unknown")}")
