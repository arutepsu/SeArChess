package chess.adapter.http4s.route

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import java.util.UUID
import scala.util.control.NonFatal

trait HistoryArchiveClient:
  def findByOwner(ownerUserId: UUID): Either[HistoryArchiveClientError, ujson.Value]

enum HistoryArchiveClientError:
  case UpstreamFailure(message: String)
  case InvalidResponse(message: String)

final class HttpHistoryArchiveClient(
    baseUrl: String,
    timeoutMillis: Int = 2000,
    client: HttpClient = HttpClient.newHttpClient()
) extends HistoryArchiveClient:

  override def findByOwner(ownerUserId: UUID): Either[HistoryArchiveClientError, ujson.Value] =
    try
      val request = HttpRequest
        .newBuilder(URI.create(s"${baseUrl.stripSuffix("/")}/internal/archives?ownerUserId=$ownerUserId"))
        .timeout(Duration.ofMillis(timeoutMillis.toLong))
        .GET()
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() match
        case 200 =>
          try Right(ujson.read(response.body()))
          catch case NonFatal(e) => Left(HistoryArchiveClientError.InvalidResponse(e.getMessage))
        case status =>
          Left(HistoryArchiveClientError.UpstreamFailure(s"history-service returned HTTP $status"))
    catch case NonFatal(e) =>
      Left(HistoryArchiveClientError.UpstreamFailure(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))
