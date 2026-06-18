package chess.tournamentservice

import cats.effect.IO
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.control.NonFatal

sealed trait ImportError
object ImportError:
  final case class NotFound(tournamentId: String)      extends ImportError
  final case class NotFinished(tournamentId: String)   extends ImportError
  final case class HttpError(status: Int, body: String) extends ImportError
  final case class ParseError(message: String)         extends ImportError
  final case class NetworkError(message: String)       extends ImportError

trait PublicTournamentClient:
  def fetchExport(serverBaseUrl: String, tournamentId: String): IO[Either[ImportError, PublicTournamentExport]]

final class JdkPublicTournamentClient(
    underlying: HttpClient = HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(15))
      .build()
) extends PublicTournamentClient:

  def fetchExport(serverBaseUrl: String, tournamentId: String): IO[Either[ImportError, PublicTournamentExport]] =
    IO.blocking {
      val url = s"${serverBaseUrl.stripSuffix("/")}/api/tournament/$tournamentId/analytics-export"
      val req = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("Accept", "application/json")
        .GET()
        .build()
      underlying.send(req, HttpResponse.BodyHandlers.ofString())
    }.flatMap { resp =>
      IO.pure(resp.statusCode() match
        case 200 => PublicExportParser.parse(resp.body())
        case 404 => Left(ImportError.NotFound(tournamentId))
        case 409 => Left(ImportError.NotFinished(tournamentId))
        case n   => Left(ImportError.HttpError(n, resp.body().take(200)))
      )
    }.handleErrorWith:
      case NonFatal(e) =>
        IO.pure(Left(ImportError.NetworkError(Option(e.getMessage).getOrElse("network error").take(200))))
