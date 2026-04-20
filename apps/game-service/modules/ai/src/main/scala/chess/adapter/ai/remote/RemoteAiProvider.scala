package chess.adapter.ai.remote

import chess.application.port.ai.{AIError, AiMoveSuggestionClient, AIRequestContext, AIResponse}
import chess.domain.model.{Move, PieceType, Position}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.control.NonFatal

/** HTTP adapter for a future remote AI Service.
 *
 *  Implements the application [[AiMoveSuggestionClient]] port so Game Service and
 *  AI turn orchestration remain unchanged. The remote service still only
 *  proposes a move; Game Service validates and applies it through the normal
 *  command path.
 */
class RemoteAiProvider(
  baseUrl:          String,
  timeoutMillis:    Int,
  defaultEngineId:  Option[String] = None,
  client:           HttpClient = HttpClient.newHttpClient()
) extends AiMoveSuggestionClient:

  private val endpoint: URI =
    URI.create(s"${baseUrl.stripSuffix("/")}/v1/move-suggestions")

  override def suggestMove(context: AIRequestContext): Either[AIError, AIResponse] =
    for
      request <- RemoteAiRequestMapper
                   .toRequest(
                     context         = context,
                     timeoutMillis   = timeoutMillis,
                     defaultEngineId = defaultEngineId
                   )
                   .left.map(err => AIError.MalformedResponse(s"failed to build AI request: $err"))
      response <- send(request)
      move     <- toDomainMove(response.move)
    yield AIResponse(move)

  private def send(requestDto: RemoteAiMoveSuggestionRequest): Either[AIError, RemoteAiMoveSuggestionResponse] =
    val body = RemoteAiJson.requestToJson(requestDto)
    val request = HttpRequest
      .newBuilder(endpoint)
      .timeout(Duration.ofMillis(timeoutMillis.toLong))
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()

    try
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() match
        case status if status >= 200 && status < 300 =>
          RemoteAiJson.responseFromJson(response.body()).left.map(AIError.MalformedResponse.apply)
        case _ =>
          Left(mapRemoteError(response.statusCode(), response.body()))
    catch
      case _: java.net.http.HttpTimeoutException =>
        Left(AIError.Timeout(s"timed out after ${timeoutMillis}ms"))
      case NonFatal(e) =>
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
