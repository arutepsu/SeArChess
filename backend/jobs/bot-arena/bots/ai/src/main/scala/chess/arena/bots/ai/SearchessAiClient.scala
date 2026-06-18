package chess.arena.bots.ai

import chess.adapter.ai.remote.{RemoteAiMoveSuggestionRequest, RemoteAiMoveSuggestionResponse}

enum SearchessAiClientError:
  case ConnectionFailed(message: String)
  case Timeout(message: String)
  case BadResponse(statusCode: Int, body: String)
  case ParseError(message: String)

  def describe: String = this match
    case ConnectionFailed(msg) => s"Connection failed: $msg"
    case Timeout(msg)          => s"Request timed out: $msg"
    case BadResponse(code, _)  => s"HTTP $code from AI service"
    case ParseError(msg)       => s"Response parse error: $msg"

trait SearchessAiClient:
  def suggestMove(
      request: RemoteAiMoveSuggestionRequest
  ): Either[SearchessAiClientError, RemoteAiMoveSuggestionResponse]
