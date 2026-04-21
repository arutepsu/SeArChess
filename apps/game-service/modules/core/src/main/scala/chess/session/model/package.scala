/** Data models for game sessions at the application layer.
  *
  * Responsibilities:
  *   - session identity and metadata (e.g. SessionId, PlayerInfo, SessionStatus)
  *   - GameSession: pairs a chess.domain.state.GameState with session metadata
  *
  * These are application-layer models, not domain models. They may reference domain types
  * (GameState, Color) but must not contain domain rules.
  *
  * Not yet populated.
  */
package chess.application.session.model
