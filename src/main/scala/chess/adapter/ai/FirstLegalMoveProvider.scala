package chess.adapter.ai

import chess.application.port.ai.{AIError, AIProvider, AIResponse}
import chess.domain.rules.GameStateRules
import chess.domain.state.GameState

/** Minimal AI provider that selects the first legal move in a deterministic order.
 *
 *  This is a stub implementation — not a chess engine.  Its purpose is to make
 *  the AI boundary real and testable without requiring an external engine.
 *
 *  Move order is determined by `(from.file, from.rank, to.file, to.rank)` so
 *  the selected move is stable across test runs and JVM instances.
 *
 *  A real engine implementation (minimax, Stockfish bridge, etc.) replaces
 *  this adapter in a later phase; this class should remain clearly labelled
 *  as a stub and not be promoted to production play.
 */
class FirstLegalMoveProvider extends AIProvider:

  override def suggestMove(state: GameState): Either[AIError, AIResponse] =
    GameStateRules
      .legalMoves(state)
      .toSeq
      .sortBy(m => (m.from.file, m.from.rank, m.to.file, m.to.rank))
      .headOption match
        case Some(move) => Right(AIResponse(move))
        case None       => Left(AIError.NoLegalMove)
