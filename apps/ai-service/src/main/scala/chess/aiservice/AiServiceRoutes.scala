package chess.aiservice

import cats.effect.IO
<<<<<<< HEAD
import chess.adapter.ai.remote.{
  RemoteAiErrorResponse,
  RemoteAiJson,
  RemoteAiMoveDto,
  RemoteAiMoveSuggestionRequest,
  RemoteAiMoveSuggestionResponse,
  RemoteAiServiceContract
}
import chess.observability.StructuredLog
=======
import chess.adapter.ai.remote.{RemoteAiErrorResponse, RemoteAiJson, RemoteAiMoveSuggestionResponse, RemoteAiServiceContract}
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

<<<<<<< HEAD
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
  * local Minimax selection and logs `ai_proxy_fallback`.
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
=======
import java.time.Instant

/** Internal-only AI HTTP surface.
 *
 *  Game Service is the intended caller for `/v1/move-suggestions`; clients and
 *  the public edge should not route directly to this service.
 */
class AiServiceRoutes(config: AiServiceConfig):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "health" =>
      json(Status.Ok, ujson.Obj(
        "status"  -> "ok",
        "service" -> "searchess-ai-service",
        "engine"  -> config.engineId,
        "audience" -> RemoteAiServiceContract.Audience
      ))
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)

    case req @ POST -> Root / "v1" / "move-suggestions" =>
      req.bodyText.compile.string.flatMap(handleSuggestion)
  }

  private def handleSuggestion(body: String): IO[Response[IO]] =
    RemoteAiJson.requestFromJson(body) match
      case Left(msg) =>
<<<<<<< HEAD
        StructuredLog.warn("ai-service", "ai_request_invalid", "error" -> msg)
        error(Status.BadRequest, "", "BAD_REQUEST", msg)
      case Right(request) if request.legalMoves.isEmpty =>
        logWarn(
          "ai_request_rejected",
          request,
          "code"   -> "BAD_REQUEST",
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
<<<<<<< HEAD
        config.pythonAiBaseUrl match
          case Some(baseUrl) => proxyWithFallback(request, body, baseUrl, started)
          case None          => selectLocal(request, started)

  /** Forwards the original request body to the Python AI service.
    * Falls back to local Minimax selection on any non-2xx response or exception.
    */
  private def proxyWithFallback(
      request: RemoteAiMoveSuggestionRequest,
      originalBody: String,
      baseUrl: String,
      started: Instant
  ): IO[Response[IO]] =
    val upstreamUrl = s"$baseUrl/v1/move-suggestions"
    IO.blocking {
      val httpRequest = HttpRequest.newBuilder()
        .uri(URI.create(upstreamUrl))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(originalBody))
        .timeout(Duration.ofMillis(config.pythonAiTimeoutMillis.toLong))
        .build()
      httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
    }.flatMap { response =>
      if response.statusCode() >= 200 && response.statusCode() < 300 then
        val elapsedMs = elapsed(started)
=======

        val selected = chess.notation.fen.FenNotationFacade.parseAndImport(
          chess.notation.api.NotationFormat.FEN,
          request.fen,
          chess.notation.api.ImportTarget.PositionTarget
        ) match
          case Right(res: chess.notation.api.ImportResult.PositionImportResult[chess.domain.state.GameState]) =>
            chess.aiservice.engine.MinimaxEngine.selectBestMove(res.data, request.legalMoves, 3).getOrElse(request.legalMoves.head)
          case Right(res: chess.notation.api.ImportResult.GameImportResult[chess.domain.state.GameState]) =>
            chess.aiservice.engine.MinimaxEngine.selectBestMove(res.data, request.legalMoves, 3).getOrElse(request.legalMoves.head)
          case _ =>
            request.legalMoves.head

        val elapsed =
          math.max(0L, java.time.Duration.between(started, Instant.now()).toMillis).toInt
>>>>>>> 97d0df0b (added ai for lichess)
        logInfo(
          "ai_proxy_succeeded",
          request,
          "elapsedMillis"  -> elapsedMs,
          "upstreamUrl"    -> upstreamUrl,
          "upstreamStatus" -> response.statusCode()
        )
        json(Status.Ok, ujson.read(response.body()))
      else
        StructuredLog.warn(
          "ai-service",
          "ai_proxy_upstream_error",
          "requestId"      -> request.requestId,
          "upstreamUrl"    -> upstreamUrl,
          "upstreamStatus" -> response.statusCode()
        )
        selectLocal(request, started)
    }.handleErrorWith { err =>
      StructuredLog.warn(
        "ai-service",
        "ai_proxy_fallback",
        "requestId"   -> request.requestId,
        "upstreamUrl" -> upstreamUrl,
        "reason"      -> err.getClass.getSimpleName,
        "message"     -> Option(err.getMessage).getOrElse("")
      )
      selectLocal(request, started)
    }

  /** Selects a move locally (Minimax with FEN fallback) and returns a 200 response. */
  private def selectLocal(request: RemoteAiMoveSuggestionRequest, started: Instant): IO[Response[IO]] =
    val selected  = chooseMove(request)
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

  /** Runs Minimax on the parsed FEN position; falls back to `legalMoves.head` if FEN is invalid. */
  private def chooseMove(request: RemoteAiMoveSuggestionRequest): RemoteAiMoveDto =
    chess.notation.fen.FenNotationFacade.parseAndImport(
      chess.notation.api.NotationFormat.FEN,
      request.fen,
      chess.notation.api.ImportTarget.PositionTarget
    ) match
      case Right(res: chess.notation.api.ImportResult.PositionImportResult[chess.domain.state.GameState]) =>
        chess.aiservice.engine.MinimaxEngine
          .selectBestMove(res.data, request.legalMoves, 4)
          .getOrElse(request.legalMoves.head)
      case Right(res: chess.notation.api.ImportResult.GameImportResult[chess.domain.state.GameState]) =>
        chess.aiservice.engine.MinimaxEngine
          .selectBestMove(res.data, request.legalMoves, 4)
          .getOrElse(request.legalMoves.head)
      case _ =>
        request.legalMoves.head

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
      request: RemoteAiMoveSuggestionRequest,
      fields: (String, Any)*
  ): Unit =
    StructuredLog.info("ai-service", event, (requestFields(request) ++ fields)*)

  private def logWarn(
      event: String,
      request: RemoteAiMoveSuggestionRequest,
      fields: (String, Any)*
  ): Unit =
    StructuredLog.warn("ai-service", event, (requestFields(request) ++ fields)*)

  private def requestFields(request: RemoteAiMoveSuggestionRequest): Seq[(String, Any)] =
    Seq(
      "requestId"  -> request.requestId,
      "gameId"     -> request.gameId,
      "sessionId"  -> request.sessionId,
      "sideToMove" -> request.sideToMove,
      "engineId"   -> request.engine.engineId
    )
=======
        error(Status.BadRequest, "", "BAD_REQUEST", msg)
      case Right(request) if request.legalMoves.isEmpty =>
        error(Status.BadRequest, request.requestId, "BAD_REQUEST", "legalMoves must contain at least one move")
      case Right(request) =>
        val started = Instant.now()
        val selected = request.legalMoves.head
        val elapsed = math.max(0L, java.time.Duration.between(started, Instant.now()).toMillis).toInt
        json(Status.Ok, ujson.read(RemoteAiJson.responseToJson(RemoteAiMoveSuggestionResponse(
          requestId     = request.requestId,
          move          = selected,
          engineId      = Some(request.engine.engineId.getOrElse(config.engineId)),
          engineVersion = Some("0.1.0"),
          elapsedMillis = Some(elapsed),
          confidence    = Some(1.0)
        ))))

  private def json(status: Status, body: ujson.Value): IO[Response[IO]] =
    IO.pure(Response[IO](
      status = status,
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(ujson.write(body).getBytes("UTF-8")).covary[IO]
    ))

  private def error(status: Status, requestId: String, code: String, message: String): IO[Response[IO]] =
    val body = RemoteAiJson.errorToJson(RemoteAiErrorResponse(requestId, code, message))
    json(status, ujson.read(body))
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
