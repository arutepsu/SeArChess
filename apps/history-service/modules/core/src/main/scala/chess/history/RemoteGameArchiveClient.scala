package chess.history

import chess.application.query.game.GameArchiveSnapshot
import chess.application.session.model.SessionIds.GameId
import chess.observability.StructuredLog
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

    val started = System.nanoTime()
    StructuredLog.info(
      "history-service",
      "game_archive_fetch_started",
      "gameId" -> gameId.value.toString,
      "uri" -> uri.toString,
      "timeoutMillis" -> timeoutMillis
    )

    try
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      val elapsed = elapsedMillis(started)
      response.statusCode() match
        case status if status >= 200 && status < 300 =>
          ArchiveSnapshotJson.fromJson(response.body()) match
            case Right(snapshot) =>
              StructuredLog.info(
                "history-service",
                "game_archive_fetch_succeeded",
                "gameId" -> gameId.value.toString,
                "status" -> status,
                "elapsedMillis" -> elapsed
              )
              Right(snapshot)
            case Left(error) =>
              StructuredLog.warn(
                "history-service",
                "game_archive_fetch_decode_failed",
                "gameId" -> gameId.value.toString,
                "status" -> status,
                "elapsedMillis" -> elapsed,
                "error" -> error
              )
              Left(GameArchiveClientError.DecodeFailure(error))
        case 404 =>
          StructuredLog.warn(
            "history-service",
            "game_archive_fetch_not_found",
            "gameId" -> gameId.value.toString,
            "status" -> 404,
            "elapsedMillis" -> elapsed
          )
          Left(GameArchiveClientError.NotFound(gameId))
        case 409 =>
          StructuredLog.warn(
            "history-service",
            "game_archive_fetch_not_ready",
            "gameId" -> gameId.value.toString,
            "status" -> 409,
            "elapsedMillis" -> elapsed
          )
          Left(GameArchiveClientError.NotReady(gameId))
        case status =>
          StructuredLog.warn(
            "history-service",
            "game_archive_fetch_failed",
            "gameId" -> gameId.value.toString,
            "status" -> status,
            "elapsedMillis" -> elapsed
          )
          Left(GameArchiveClientError.TransportFailure(s"Game Service returned HTTP $status: ${response.body()}"))
    catch
      case NonFatal(e) =>
        StructuredLog.warn(
          "history-service",
          "game_archive_fetch_transport_failed",
          "gameId" -> gameId.value.toString,
          "elapsedMillis" -> elapsedMillis(started),
          "error" -> e.getMessage
        )
        Left(GameArchiveClientError.TransportFailure(e.getMessage))

  private def elapsedMillis(startedNanos: Long): Long =
    (System.nanoTime() - startedNanos) / 1000000L
