package chess.adapter.ai.remote

import chess.application.port.ai.{AIError, AiMoveSuggestionClient, AIRequestContext, AIResponse}
import chess.domain.model.{Move, PieceType, Position}
import chess.observability.StructuredLog

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.control.NonFatal

/** HTTP adapter for the standalone remote AI Service.
 *
 *  Implements the application [[AiMoveSuggestionClient]] port so Game Service and
 *  AI turn orchestration remain unchanged. The remote service still only
 *  proposes a move; Game Service validates and applies it through the normal
 *  command path.
 */
class RemoteAiMoveSuggestionClient(
  baseUrl:          String,
  timeoutMillis:    Int,
  defaultEngineId:  Option[String] = None,
  testMode:         Option[String] = None,
  client:           HttpClient = HttpClient.newHttpClient()
) extends AiMoveSuggestionClient:

  private val TestModeHeader = "X-Searchess-AI-Test-Mode"

  private val endpoint: URI =
    URI.create(s"${baseUrl.stripSuffix("/")}${RemoteAiServiceContract.MoveSuggestionsPath}")

  override def suggestMove(context: AIRequestContext): Either[AIError, AIResponse] =
    val request =
      RemoteAiRequestMapper
        .toRequest(
          context         = context,
          timeoutMillis   = timeoutMillis,
          defaultEngineId = defaultEngineId
        )
        .left.map { err =>
          StructuredLog.warn(
            "game-service",
            "ai_request_build_failed",
                "requestId" -> context.requestId,
                "gameId" -> context.gameId.value.toString,
                "sessionId" -> context.sessionId.value.toString,
                "sideToMove" -> context.sideToMove.toString.toLowerCase,
                "error" -> err.toString
              )
          AIError.MalformedResponse(s"failed to build AI request: $err")
        }

    request.flatMap { requestDto =>
      send(requestDto).flatMap { response =>
        toDomainMove(response.move).left.map { err =>
          logWarn("ai_response_move_invalid", requestDto, "error" -> describe(err))
          err
        }.map(AIResponse.apply)
      }
    }

  private def send(requestDto: RemoteAiMoveSuggestionRequest): Either[AIError, RemoteAiMoveSuggestionResponse] =
    val body = RemoteAiJson.requestToJson(requestDto)
    val requestBuilder = HttpRequest
      .newBuilder(endpoint)
      .timeout(Duration.ofMillis(timeoutMillis.toLong))
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
    testMode.foreach(mode => requestBuilder.header(TestModeHeader, mode))
    val request = requestBuilder.build()

    val started = System.nanoTime()
    logInfo(
      "ai_request_started",
      requestDto,
      "endpoint" -> endpoint.toString,
      "timeoutMillis" -> timeoutMillis,
      "legalMoveCount" -> requestDto.legalMoves.size
    )

    try
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      val elapsed = elapsedMillis(started)
      response.statusCode() match
        case status if status >= 200 && status < 300 =>
          RemoteAiJson.responseFromJson(response.body()) match
            case Right(parsed) =>
              logInfo(
                "ai_request_succeeded",
                requestDto,
                "status" -> status,
                "elapsedMillis" -> elapsed,
                "responseEngineId" -> parsed.engineId,
                "move" -> s"${parsed.move.from}${parsed.move.to}"
              )
              Right(parsed)
            case Left(err) =>
              logWarn(
                "ai_response_malformed",
                requestDto,
                "status" -> status,
                "elapsedMillis" -> elapsed,
                "error" -> err
              )
              Left(AIError.MalformedResponse(err))
        case _ =>
          val error = mapRemoteError(response.statusCode(), response.body())
          logWarn(
            "ai_request_failed",
            requestDto,
            "status" -> response.statusCode(),
            "elapsedMillis" -> elapsed,
            "error" -> describe(error)
          )
          Left(error)
    catch
      case _: java.net.http.HttpTimeoutException =>
        logWarn(
          "ai_request_timeout",
          requestDto,
          "elapsedMillis" -> elapsedMillis(started),
          "timeoutMillis" -> timeoutMillis
        )
        Left(AIError.Timeout(s"timed out after ${timeoutMillis}ms"))
      case NonFatal(e) =>
        logWarn(
          "ai_request_transport_failed",
          requestDto,
          "elapsedMillis" -> elapsedMillis(started),
          "error" -> e.getMessage
        )
        Left(AIError.Unavailable(s"transport failure: ${e.getMessage}"))

  private def mapRemoteError(status: Int, body: String): AIError =
    RemoteAiJson.errorFromJson(body) match
      case Some(RemoteAiErrorResponse(_, "NO_LEGAL_MOVE", _)) =>
        AIError.NoLegalMove
      case Some(RemoteAiErrorResponse(_, "ENGINE_UNAVAILABLE", message)) =>
        AIError.Unavailable(message)
      case Some(RemoteAiErrorResponse(_, "ENGINE_TIMEOUT", message)) =>
        AIError.Timeout(message)
      case Some(RemoteAiErrorResponse(_, code, message)) =>
        AIError.EngineFailure(s"$code: $message")
      case None =>
        AIError.Unavailable(s"remote AI service returned HTTP $status")

  private def toDomainMove(dto: RemoteAiMoveDto): Either[AIError, Move] =
    for
      from      <- Position.fromAlgebraic(dto.from)
                     .left.map(err => AIError.MalformedResponse(s"invalid AI move from '${dto.from}': $err"))
      to        <- Position.fromAlgebraic(dto.to)
                     .left.map(err => AIError.MalformedResponse(s"invalid AI move to '${dto.to}': $err"))
      promotion <- parsePromotion(dto.promotion)
    yield Move(from, to, promotion)

  private def parsePromotion(value: Option[String]): Either[AIError, Option[PieceType]] =
    value match
      case None => Right(None)
      case Some(raw) =>
        PieceType.values
          .find(_.toString.equalsIgnoreCase(raw))
          .map(pt => Right(Some(pt)))
          .getOrElse(Left(AIError.MalformedResponse(s"invalid AI promotion piece: '$raw'")))

  private def fieldsFor(request: RemoteAiMoveSuggestionRequest): Seq[(String, Any)] =
    Seq(
      "requestId" -> request.requestId,
      "gameId" -> request.gameId,
      "sessionId" -> request.sessionId,
      "sideToMove" -> request.sideToMove,
      "engineId" -> request.engine.engineId
    )

  private def logInfo(event: String, request: RemoteAiMoveSuggestionRequest, fields: (String, Any)*): Unit =
    StructuredLog.info("game-service", event, (fieldsFor(request) ++ fields)*)

  private def logWarn(event: String, request: RemoteAiMoveSuggestionRequest, fields: (String, Any)*): Unit =
    StructuredLog.warn("game-service", event, (fieldsFor(request) ++ fields)*)

  private def elapsedMillis(startedNanos: Long): Long =
    (System.nanoTime() - startedNanos) / 1000000L

  private def describe(error: AIError): String = error match
    case AIError.NoLegalMove              => "NoLegalMove"
    case AIError.Unavailable(message)     => s"Unavailable: $message"
    case AIError.Timeout(message)         => s"Timeout: $message"
    case AIError.EngineFailure(message)   => s"EngineFailure: $message"
    case AIError.MalformedResponse(error) => s"MalformedResponse: $error"
