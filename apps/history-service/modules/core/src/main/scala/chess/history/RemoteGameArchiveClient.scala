package chess.history

import chess.application.query.game.GameArchiveSnapshot
import chess.application.session.model.SessionIds.GameId
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.control.NonFatal

enum GameArchiveClientError:
  case NotFound(gameId: GameId)
  case NotReady(gameId: GameId)
  case TransportFailure(message: String)
  case DecodeFailure(message: String)

class RemoteGameArchiveClient(
  baseUrl:       String,
  timeoutMillis: Int = 2000,
  client:        HttpClient = HttpClient.newHttpClient()
):

  def fetch(gameId: GameId): Either[GameArchiveClientError, GameArchiveSnapshot] =
    val uri = URI.create(s"${baseUrl.stripSuffix("/")}/archive/games/${gameId.value}")
    val request = HttpRequest
      .newBuilder(uri)
      .timeout(Duration.ofMillis(timeoutMillis.toLong))
      .header("Accept", "application/json")
      .GET()
      .build()

    try
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() match
        case status if status >= 200 && status < 300 =>
          ArchiveSnapshotJson.fromJson(response.body()).left.map(GameArchiveClientError.DecodeFailure(_))
        case 404 =>
          Left(GameArchiveClientError.NotFound(gameId))
        case 409 =>
          Left(GameArchiveClientError.NotReady(gameId))
        case status =>
          Left(GameArchiveClientError.TransportFailure(s"Game Service returned HTTP $status: ${response.body()}"))
    catch
      case NonFatal(e) => Left(GameArchiveClientError.TransportFailure(e.getMessage))
