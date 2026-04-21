package chess.adapter.ai

import chess.application.port.ai.{AIError, AiMoveSuggestionClient, AIRequestContext, AIResponse}
import chess.domain.rules.GameStateRules

/** Dev/test AI client that selects the first legal move in a deterministic order.
  *
  * This is a local deterministic fallback, not the canonical runtime AI path. Production-like Game
  * Service runtime should use the remote AI service via
  * [[chess.adapter.ai.remote.RemoteAiMoveSuggestionClient]].
  *
  * Move order is determined by `(from.file, from.rank, to.file, to.rank)` so the selected move is
  * stable across test runs and JVM instances.
  */
class LocalDeterministicAiClient extends AiMoveSuggestionClient:

  override def suggestMove(context: AIRequestContext): Either[AIError, AIResponse] =
    val state = context.state
    GameStateRules
      .legalMoves(state)
      .toSeq
      .sortBy(m => (m.from.file, m.from.rank, m.to.file, m.to.rank))
      .headOption match
      case Some(move) => Right(AIResponse(move))
      case None       => Left(AIError.NoLegalMove)
