package chess.application.ai.service

import chess.application.session.model.GameSession
import chess.domain.state.GameState

/** Why [[AITurnService.runTurns]] stopped advancing turns. */
enum AITurnRunStopReason:
  /** The game reached a terminal state (checkmate, draw, or resignation). */
  case GameFinished
  /** The side to move is human-controlled; the run yielded control back to the caller. */
  case AwaitingHuman
  /** The caller-supplied `maxPlies` limit was consumed before the game or human turn. */
  case MaxPliesReached
  /** An AI turn failed mid-run. `pliesRun` turns completed successfully before the failure. */
  case MoveFailed(cause: AITurnError)

/** Result of a bounded AI turn run via [[AITurnService.runTurns]].
  *
  * @param state      game state after `pliesRun` AI turns
  * @param session    session after `pliesRun` AI turns
  * @param pliesRun   number of AI turns successfully applied
  * @param stopReason why the loop stopped
  */
final case class AITurnRunResult(
    state: GameState,
    session: GameSession,
    pliesRun: Int,
    stopReason: AITurnRunStopReason
)
