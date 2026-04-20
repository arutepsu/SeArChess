package chess.application.port.ai

import chess.domain.model.Move

/** The result of a successful [[AiMoveSuggestionClient.suggestMove]] call.
 *
 *  A dedicated response type — rather than a bare [[Move]] — keeps the port
 *  contract extensible: engine metadata (evaluation score, search depth, etc.)
 *  can be added here without changing the trait signature.
 *
 *  @param move the move the engine proposes for the current player
 */
final case class AIResponse(move: Move)
