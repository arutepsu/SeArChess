/** Application-facing AI orchestration services.
  *
  * Responsibilities:
  *   - orchestrating AI move requests: receive GameState, call port.ai.AiEngine, return Move
  *   - coordinating AI difficulty selection with ai.policy
  *   - converting domain Move results back to application command inputs
  *
  * Does NOT implement AI logic — that belongs behind the port.ai.AiEngine interface. Does NOT
  * contain difficulty/behaviour rules — those belong in chess.application.ai.policy.
  *
  * Not yet populated.
  */
package chess.application.ai.service
