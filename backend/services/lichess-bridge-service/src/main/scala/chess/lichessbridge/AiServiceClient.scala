package chess.lichessbridge

import cats.effect.IO
import chess.adapter.ai.remote.{
  RemoteAiEngineSelection,
  RemoteAiJson,
  RemoteAiLimits,
  RemoteAiMetadata,
  RemoteAiMoveDto,
  RemoteAiMoveSuggestionRequest
}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.control.NonFatal

// ── AI bridge domain types ─────────────────────────────────────────────────────

final case class AiMoveRequest(
    gameId: String,
    fen: String,
    legalMoves: List[RemoteAiMoveDto],
    sideToMove: String
)

final case class UciMove(value: String)

enum AiError:
  case NoLegalMoves
  case ServiceError(message: String)
  case Timeout(message: String)
  case InvalidResponse(message: String)

// ── Client trait ──────────────────────────────────────────────────────────────

trait AiServiceClient[F[_]]:
  def suggestMove(request: AiMoveRequest): F[Either[AiError, UciMove]]

// ── JDK implementation ────────────────────────────────────────────────────────

final class JdkAiServiceClient(
    baseUrl: String,
    underlying: HttpClient = HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build()
) extends AiServiceClient[IO]:

  private val requestTimeout = Duration.ofSeconds(15)

  def suggestMove(request: AiMoveRequest): IO[Either[AiError, UciMove]] =
    if request.legalMoves.isEmpty then
      IO.pure(Left(AiError.NoLegalMoves))
    else
      IO.blocking {
        val requestId = java.util.UUID.randomUUID().toString
        val body = RemoteAiJson.requestToJson(
          RemoteAiMoveSuggestionRequest(
            requestId  = requestId,
            gameId     = request.gameId,
            sessionId  = request.gameId,
            sideToMove = request.sideToMove,
            fen        = request.fen,
            legalMoves = request.legalMoves,
            engine     = RemoteAiEngineSelection(None),
            limits     = RemoteAiLimits(timeoutMillis = 5000),
            metadata   = RemoteAiMetadata("LichessBridge")
          )
        )
        val req = HttpRequest
          .newBuilder(URI.create(s"$baseUrl/v1/move-suggestions"))
          .timeout(requestTimeout)
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build()
        underlying.send(req, HttpResponse.BodyHandlers.ofString())
      }.flatMap { resp =>
        IO.pure(
          if resp.statusCode() >= 200 && resp.statusCode() < 300 then
            RemoteAiJson.responseFromJson(resp.body()) match
              case Right(r) =>
                Right(UciMove(r.move.from + r.move.to + r.move.promotion.getOrElse("")))
              case Left(err) =>
                Left(AiError.InvalidResponse(err))
          else
            Left(AiError.ServiceError(s"ai-service HTTP ${resp.statusCode()}"))
        )
      }.handleErrorWith {
        case e: java.net.http.HttpTimeoutException =>
          IO.pure(Left(AiError.Timeout(Option(e.getMessage).getOrElse("timeout").take(120))))
        case NonFatal(e) =>
          IO.pure(Left(AiError.ServiceError(Option(e.getMessage).getOrElse("unknown error").take(120))))
      }
