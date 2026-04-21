/** Outbound port: persistence abstraction for game and session data.
  *
  * Responsibilities:
  *   - interface definitions for loading and persisting GameState / GameSession (e.g.
  *     GameRepository, SessionRepository)
  *   - no implementation — implementations live in chess.adapter.* packages
  *
  * Example interface to introduce when persistence is needed:
  * {{{
  *  trait GameRepository:
  *    def load(id: SessionId): Either[RepositoryError, GameSession]
  *    def save(session: GameSession): Either[RepositoryError, Unit]
  * }}}
  *
  * Not yet populated.
  */
package chess.application.port.repository
