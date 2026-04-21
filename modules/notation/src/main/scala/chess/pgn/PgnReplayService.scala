package chess.notation.pgn

import chess.domain.rules.GameStateRules
import chess.domain.state.GameState
import chess.notation.api.{NotationFailure, ValidationFailure}

/** Replays a sequence of SAN move tokens from an explicitly supplied starting position.
  *
  * Each token is resolved to a [[chess.domain.model.Move]] via [[SanResolver]], then applied to the
  * evolving [[GameState]] via [[GameStateRules.applyMove]]. Replay stops and returns a failure on
  * the first token that cannot be resolved or applied.
  *
  * Visible only within the `chess.notation.pgn` package.
  */
private[pgn] object PgnReplayService:

  /** Replay `tokens` from `initialState`.
    *
    * @param initialState
    *   the starting [[GameState]] — callers choose this explicitly
    * @param tokens
    *   SAN half-move tokens extracted from a PGN mainline
    * @return
    *   the final [[GameState]] after all moves are applied, or a [[NotationFailure]] describing the
    *   first error
    */
  def replayFrom(
      initialState: GameState,
      tokens: Vector[String]
  ): Either[NotationFailure, GameState] =
    tokens.foldLeft[Either[NotationFailure, GameState]](Right(initialState)) {
      case (Left(err), _) => Left(err)
      case (Right(state), san) =>
        SanResolver.resolve(state, san).flatMap { move =>
          GameStateRules.applyMove(state, move).left.map { domainErr =>
            ValidationFailure.InvalidValue(
              "san",
              san,
              s"Move implied by SAN '$san' could not be applied: $domainErr"
            )
          }
        }
    }
