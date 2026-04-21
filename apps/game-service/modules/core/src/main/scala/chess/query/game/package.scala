/** Application use cases that read game state without mutating it.
  *
  * Responsibilities:
  *   - query types for read-only game projections (e.g. LegalTargetsQuery, GameStatusQuery)
  *   - query handlers that delegate pure reads to chess.domain.rules or chess.domain.state
  *
  * Candidate for migration from chess.application.ChessService:
  *   - ChessService.legalTargetsFrom → LegalTargetsQuery handler
  *
  * Does NOT mutate state, produce domain events, or call command handlers.
  */
package chess.application.query.game
