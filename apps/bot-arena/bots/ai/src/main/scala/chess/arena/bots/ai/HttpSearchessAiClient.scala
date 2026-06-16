package chess.arena.bots.ai

import chess.adapter.ai.remote.{RemoteAiJson, RemoteAiMoveSuggestionRequest, RemoteAiMoveSuggestionResponse}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

final class HttpSearchessAiClient(config: AiServiceBotConfig) extends SearchessAiClient:

  private val httpClient: HttpClient =
    HttpClient.newBuilder()
      .connectTimeout(Duration.ofMillis(config.timeoutMillis))
      .build()

  def suggestMove(
      request: RemoteAiMoveSuggestionRequest
  ): Either[SearchessAiClientError, RemoteAiMoveSuggestionResponse] =
    val uri     = URI.create(s"${config.baseUrl}${config.endpointPath}")
    val body    = RemoteAiJson.requestToJson(request)
    val httpReq = HttpRequest.newBuilder(uri)
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .header("Content-Type", "application/json")
      .timeout(Duration.ofMillis(config.timeoutMillis))
      .build()

    val responseOrError: Either[SearchessAiClientError, HttpResponse[String]] =
      try Right(httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString()))
      catch
        case e: java.net.ConnectException =>
          Left(SearchessAiClientError.ConnectionFailed(e.getMessage))
        case e: java.net.http.HttpTimeoutException =>
          Left(SearchessAiClientError.Timeout(e.getMessage))
        case e: Exception =>
          Left(SearchessAiClientError.ConnectionFailed(e.getMessage))

    responseOrError.flatMap { resp =>
      if resp.statusCode() == 200 then
        RemoteAiJson.responseFromJson(resp.body())
          .left.map(SearchessAiClientError.ParseError.apply)
      else
        Left(SearchessAiClientError.BadResponse(resp.statusCode(), resp.body()))
    }
