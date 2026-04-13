/** Application use cases that mutate game state.
 *
 *  Responsibilities:
 *  - command types that represent player intentions (e.g. ApplyMove, NewGame)
 *  - command handlers that validate guards, delegate to domain rules, and return results
 *
 *  === Current command boundary ===
 *  The game-session command surface is defined by
 *  [[chess.application.session.service.GameSessionCommands]] and implemented by
 *  [[chess.application.session.service.SessionGameService]].  These are the first
 *  extractable service boundary candidates.
 *
 *  Does NOT:
 *  - contain chess rule logic (that belongs in chess.domain.rules)
 *  - contain query / read-only use cases (those belong in chess.application.query.game)
 *  - map to or from HTTP / persistence representations
 */
package chess.application.command.game
