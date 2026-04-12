/** Application use cases that mutate session state.
 *
 *  Responsibilities:
 *  - command types for session lifecycle changes (e.g. CreateSession, ResignGame, ClaimDraw)
 *  - command handlers that enforce session-level rules and delegate to domain logic
 *
 *  Not yet populated — session lifecycle is not modelled in the current codebase.
 *  This package is a forward boundary for when multi-session or networked play is introduced.
 */
package chess.application.command.session
