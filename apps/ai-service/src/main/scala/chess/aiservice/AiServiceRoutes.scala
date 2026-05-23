package chess.aiservice

import cats.effect.IO
import chess.adapter.ai.remote.{
  RemoteAiErrorResponse,
  RemoteAiJson,
  RemoteAiMoveSuggestionRequest,
  RemoteAiMoveSuggestionResponse,
  RemoteAiServiceContract
}
import chess.observability.StructuredLog
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration, Instant}

/** Internal-only AI HTTP surface.
  *
  * Game Service is the intended caller for `/v1/move-suggestions`; clients and the public edge
  * should not route directly to this service.
  *
  * When PYTHON_AI_BASE_URL is set, move-suggestion requests are proxied to the Python AI service.
  * On any proxy failure (connection error, timeout, non-2xx response) the service falls back to
  * local selection (first legal move) and logs `ai_proxy_fallback`.
  */
class AiServiceRoutes(config: AiServiceConfig):

  private val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofMillis(config.pythonAiTimeoutMillis.toLong))
    .build()

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "health" =>
      json(
        Status.Ok,
        ujson.Obj(
          "status"    -> "ok",
          "service"   -> "searchess-ai-service",
          "engine"    -> config.engineId,
          "pythonAi"  -> config.pythonAiBaseUrl.getOrElse("disabled"),
          "audience"  -> RemoteAiServiceContract.Audience,
          "check"     -> "process-liveness"
        )
      )

    case req @ POST -> Root / "v1" / "move-suggestions" =>
      req.bodyText.compile.string.flatMap(handleSuggestion)
  }

  private def handleSuggestion(body: String): IO[Response[IO]] =
    RemoteAiJson.requestFromJson(body) match
      case Left(msg) =>
        StructuredLog.warn("ai-service", "ai_request_invalid", "error" -> msg)
        error(Status.BadRequest, "", "BAD_REQUEST", msg)
      case Right(request) if request.legalMoves.isEmpty =>
        logWarn(
          "ai_request_rejected",
          request,
          "code" -> "BAD_REQUEST",
          "reason" -> "legalMoves empty"
        )
        error(
          Status.BadRequest,
          request.requestId,
          "BAD_REQUEST",
          "legalMoves must contain at least one move"
        )
      case Right(request) =>
        val started = Instant.now()
        logInfo("ai_request_received", request, "legalMoveCount" -> request.legalMoves.size)
        config.pythonAiBaseUrl match
          case Some(baseUrl) => proxyToPython(body, request, baseUrl, started)
          case None          => selectLocal(request, started)

  private def proxyToPython(
      body: String,
      request: RemoteAiMoveSuggestionRequest,
      baseUrl: String,
      started: Instant
  ): IO[Response[IO]] =
    IO.blocking {
      val httpRequest = HttpRequest.newBuilder()
        .uri(URI.create(s"$baseUrl/v1/move-suggestions"))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofMillis(config.pythonAiTimeoutMillis.toLong))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
    }.flatMap { response =>
      val status       = response.statusCode()
      val responseBody = response.body()
      if status >= 200 && status < 300 then
        val elapsedMs = elapsed(started)
        logInfo(
          "ai_proxy_succeeded",
          request,
          "elapsedMillis" -> elapsedMs,
          "upstreamUrl"   -> baseUrl,
          "upstreamStatus" -> status
        )
        json(Status.Ok, ujson.read(responseBody))
      else
        StructuredLog.warn(
          "ai-service",
          "ai_proxy_upstream_error",
          "requestId"      -> request.requestId,
          "upstreamUrl"    -> baseUrl,
          "upstreamStatus" -> status
        )
        selectLocal(request, started)
    }.handleErrorWith { err =>
      StructuredLog.warn(
        "ai-service",
        "ai_proxy_fallback",
        "requestId" -> request.requestId,
        "upstreamUrl" -> baseUrl,
        "reason"    -> err.getClass.getSimpleName,
        "message"   -> Option(err.getMessage).getOrElse("")
      )
      selectLocal(request, started)
    }

  private def selectLocal(request: RemoteAiMoveSuggestionRequest, started: Instant): IO[Response[IO]] =
    val selected  = request.legalMoves.head
    val elapsedMs = elapsed(started)
    logInfo(
      "ai_request_succeeded",
      request,
      "elapsedMillis" -> elapsedMs,
      "selectedMove"  -> s"${selected.from}${selected.to}",
      "engineId"      -> request.engine.engineId.getOrElse(config.engineId)
    )
    json(
      Status.Ok,
      ujson.read(
        RemoteAiJson.responseToJson(
          RemoteAiMoveSuggestionResponse(
            requestId     = request.requestId,
            move          = selected,
            engineId      = Some(request.engine.engineId.getOrElse(config.engineId)),
            engineVersion = Some("0.1.0"),
            elapsedMillis = Some(elapsedMs),
            confidence    = Some(1.0)
          )
        )
      )
    )

  private def elapsed(started: Instant): Int =
    math.max(0L, Duration.between(started, Instant.now()).toMillis).toInt

  private def json(status: Status, body: ujson.Value): IO[Response[IO]] =
    IO.pure(
      Response[IO](
        status = status,
        headers = Headers(`Content-Type`(MediaType.application.json)),
        body = Stream.emits(ujson.write(body).getBytes("UTF-8")).covary[IO]
      )
    )

  private def error(
      status: Status,
      requestId: String,
      code: String,
      message: String
  ): IO[Response[IO]] =
    val body = RemoteAiJson.errorToJson(RemoteAiErrorResponse(requestId, code, message))
    json(status, ujson.read(body))

  private def logInfo(
      event: String,
      request: chess.adapter.ai.remote.RemoteAiMoveSuggestionRequest,
      fields: (String, Any)*
  ): Unit =
    StructuredLog.info("ai-service", event, (requestFields(request) ++ fields)*)

  private def logWarn(
      event: String,
      request: chess.adapter.ai.remote.RemoteAiMoveSuggestionRequest,
      fields: (String, Any)*
  ): Unit =
    StructuredLog.warn("ai-service", event, (requestFields(request) ++ fields)*)

  private def requestFields(
      request: chess.adapter.ai.remote.RemoteAiMoveSuggestionRequest
  ): Seq[(String, Any)] =
    Seq(
      "requestId" -> request.requestId,
      "gameId" -> request.gameId,
      "sessionId" -> request.sessionId,
      "sideToMove" -> request.sideToMove,
      "engineId" -> request.engine.engineId
    )
