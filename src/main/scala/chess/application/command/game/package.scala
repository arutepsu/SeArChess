/** Application use cases that mutate game state.
 *
 *  Responsibilities:
 *  - command types that represent player intentions (e.g. ApplyMove, NewGame)
 *  - command handlers that validate guards, delegate to domain rules, and return results
 *  - internal orchestration types (ApplyMoveResult, GameTransitionService, EventBuilder,
 *    MoveTransitionContext) once migrated from the application root
 *
 *  Does NOT:
 *  - contain chess rule logic (that belongs in chess.domain.rules)
 *  - contain query / read-only use cases (those belong in chess.application.query.game)
 *  - map to or from HTTP / persistence representations
 */
package chess.application.command.game
