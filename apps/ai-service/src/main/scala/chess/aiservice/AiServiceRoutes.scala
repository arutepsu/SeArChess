package chess.aiservice

import cats.effect.IO
import chess.adapter.ai.remote.{
  RemoteAiErrorResponse,
  RemoteAiJson,
  RemoteAiMoveSuggestionResponse,
  RemoteAiServiceContract
}
import chess.observability.StructuredLog
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

import java.time.Instant

/** Internal-only AI HTTP surface.
  *
  * Game Service is the intended caller for `/v1/move-suggestions`; clients and the public edge
  * should not route directly to this service.
  */
class AiServiceRoutes(config: AiServiceConfig):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "health" =>
      json(
        Status.Ok,
        ujson.Obj(
          "status" -> "ok",
          "service" -> "searchess-ai-service",
          "engine" -> config.engineId,
          "audience" -> RemoteAiServiceContract.Audience,
          "check" -> "process-liveness"
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
        val selected = request.legalMoves.head
        val elapsed =
          math.max(0L, java.time.Duration.between(started, Instant.now()).toMillis).toInt
        logInfo(
          "ai_request_succeeded",
          request,
          "elapsedMillis" -> elapsed,
          "selectedMove" -> s"${selected.from}${selected.to}",
          "engineId" -> request.engine.engineId.getOrElse(config.engineId)
        )
        json(
          Status.Ok,
          ujson.read(
            RemoteAiJson.responseToJson(
              RemoteAiMoveSuggestionResponse(
                requestId = request.requestId,
                move = selected,
                engineId = Some(request.engine.engineId.getOrElse(config.engineId)),
                engineVersion = Some("0.1.0"),
                elapsedMillis = Some(elapsed),
                confidence = Some(1.0)
              )
            )
          )
        )

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
