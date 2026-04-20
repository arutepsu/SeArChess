package chess.adapter.ai.remote

import chess.application.port.ai.AIRequestContext
import chess.application.session.model.GameSession
import chess.domain.model.Move
import chess.domain.rules.GameStateRules
import chess.domain.state.GameState
import chess.notation.api.{NotationFailure, NotationFormat}
import chess.notation.fen.FenSerializer

/** Builds adapter-level remote AI request DTOs from current Game Service data.
 *
 *  The mapper is deliberately kept out of the application layer. It prepares
 *  the future remote AI contract while preserving the existing `AIProvider`
 *  seam: Game Service still validates and applies whatever move a provider
 *  returns.
 */
object RemoteAiRequestMapper:

  def toRequest(
    context:         AIRequestContext,
    timeoutMillis:   Int,
    defaultEngineId: Option[String]
  ): Either[NotationFailure, RemoteAiMoveSuggestionRequest] =
    val state = context.state
    FenSerializer.exportNotation(state, NotationFormat.FEN).map { fen =>
      RemoteAiMoveSuggestionRequest(
        requestId  = context.requestId,
        gameId     = context.gameId.value.toString,
        sessionId  = context.sessionId.value.toString,
        sideToMove = context.sideToMove.toString.toLowerCase,
        fen        = fen.text,
        legalMoves = legalMoveDtos(state),
        engine     = RemoteAiEngineSelection(context.engineId.orElse(defaultEngineId)),
        limits     = RemoteAiLimits(timeoutMillis),
        metadata   = RemoteAiMetadata(mode = context.mode.toString)
      )
    }

  def toRequest(
    requestId:       String,
    session:         GameSession,
    state:           GameState,
    timeoutMillis:   Int,
    defaultEngineId: Option[String] = None
  ): Either[NotationFailure, RemoteAiMoveSuggestionRequest] =
    toRequest(
      context         = AIRequestContext.fromSession(session, state, requestId),
      timeoutMillis   = timeoutMillis,
      defaultEngineId = defaultEngineId
    )

  private def legalMoveDtos(state: GameState): List[RemoteAiMoveDto] =
    GameStateRules
      .legalMoves(state)
      .toList
      .sortBy(m => (m.from.file, m.from.rank, m.to.file, m.to.rank, m.promotion.map(_.toString).getOrElse("")))
      .map(moveDto)

  private def moveDto(move: Move): RemoteAiMoveDto =
    RemoteAiMoveDto(
      from      = move.from.toString,
      to        = move.to.toString,
      promotion = move.promotion.map(_.toString.toLowerCase)
    )
