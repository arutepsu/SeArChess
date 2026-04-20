package chess.adapter.ai.remote

import chess.application.port.ai.{AIError, AIProvider, AIRequestContext, AIResponse}
import chess.domain.model.{Move, PieceType, Position}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.control.NonFatal

/** HTTP adapter for a future remote AI Service.
 *
 *  Implements the existing application [[AIProvider]] port so Game Service and
 *  AI turn orchestration remain unchanged. The remote service still only
 *  proposes a move; Game Service validates and applies it through the normal
 *  command path.
 */
class RemoteAiProvider(
  baseUrl:          String,
  timeoutMillis:    Int,
  defaultEngineId:  Option[String] = None,
  client:           HttpClient = HttpClient.newHttpClient()
) extends AIProvider:

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
                   .left.map(err => AIError.EngineFailure(s"failed to build AI request: $err"))
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
          RemoteAiJson.responseFromJson(response.body()).left.map(msg => AIError.EngineFailure(msg))
        case _ =>
          Left(mapRemoteError(response.body()))
    catch
      case _: java.net.http.HttpTimeoutException =>
        Left(AIError.EngineFailure("timeout"))
      case NonFatal(e) =>
        Left(AIError.EngineFailure(s"transport failure: ${e.getMessage}"))

  private def mapRemoteError(body: String): AIError =
    RemoteAiJson.errorFromJson(body) match
      case Some(RemoteAiErrorResponse(_, "NO_LEGAL_MOVE", _)) =>
        AIError.NoLegalMove
      case Some(RemoteAiErrorResponse(_, code, message)) =>
        AIError.EngineFailure(s"$code: $message")
      case None =>
        AIError.EngineFailure("remote AI service returned an error")

  private def toDomainMove(dto: RemoteAiMoveDto): Either[AIError, Move] =
    for
      from      <- Position.fromAlgebraic(dto.from)
                     .left.map(err => AIError.EngineFailure(s"invalid AI move from '${dto.from}': $err"))
      to        <- Position.fromAlgebraic(dto.to)
                     .left.map(err => AIError.EngineFailure(s"invalid AI move to '${dto.to}': $err"))
      promotion <- parsePromotion(dto.promotion)
    yield Move(from, to, promotion)

  private def parsePromotion(value: Option[String]): Either[AIError, Option[PieceType]] =
    value match
      case None => Right(None)
      case Some(raw) =>
        PieceType.values
          .find(_.toString.equalsIgnoreCase(raw))
          .map(pt => Right(Some(pt)))
          .getOrElse(Left(AIError.EngineFailure(s"invalid AI promotion piece: '$raw'")))
