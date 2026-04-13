package chess.application.port.ai

import chess.domain.state.GameState

/** Outbound port for AI move generation.
 *
 *  An AI provider receives the current [[GameState]] and returns a proposed
 *  [[AIResponse]] or an [[AIError]] if no candidate can be produced.
 *
 *  The port is intentionally narrow: it accepts only the chess state and
 *  returns only a move candidate.  Engine configuration, depth budgets, and
 *  capability negotiation are future concerns that belong in a later phase.
 *
 *  Implementations belong in `chess.adapter.*`; the application layer depends
 *  only on this trait, never on a concrete class.
 */
trait AIProvider:

  /** Propose a move for the current player in `state`.
   *
   *  @return [[Right]] with the proposed [[AIResponse]], or [[Left]] with an
   *          [[AIError]] when the engine cannot produce a candidate.
   */
  def suggestMove(state: GameState): Either[AIError, AIResponse]
