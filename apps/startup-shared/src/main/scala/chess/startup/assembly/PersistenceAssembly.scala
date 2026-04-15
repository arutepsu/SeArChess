package chess.startup.assembly

import chess.adapter.repository.{InMemoryGameRepository, InMemorySessionGameStore, InMemorySessionRepository}
import chess.application.port.repository.{GameRepository, SessionGameStore, SessionRepository}
import chess.config.{AppConfig, PersistenceMode}

/** Assembled persistence infrastructure produced by [[PersistenceAssembly.assemble]].
 *
 *  All fields are typed to stable application port interfaces; callers are not
 *  exposed to in-memory or any other concrete repository class.
 *
 *  @param sessionRepository session read/write store
 *  @param gameRepository    game-state read store (also satisfies the read port
 *                           consumed by REST routes)
 *  @param store             combined session + game-state write store used by
 *                           [[chess.application.session.service.SessionGameService]]
 */
final case class PersistenceWiring(
  sessionRepository: SessionRepository,
  gameRepository:    GameRepository,
  store:             SessionGameStore
)

/** Assembles the persistence layer from [[AppConfig]].
 *
 *  This object is the single place in the composition root that selects and
 *  constructs repository implementations.  Adding a new backend (e.g. Postgres)
 *  means adding a `case` to [[chess.config.PersistenceMode]], a branch in
 *  [[assemble]], and a corresponding private assembly method here — nothing
 *  else in the bootstrap or application layers changes.
 *
 *  === Current strategies ===
 *  - [[PersistenceMode.InMemory]]: all state lives in JVM heap; suitable for
 *    local development and GUI/TUI apps.  Data is lost on restart.
 *
 *  === Extension path ===
 *  Future strategies (e.g. `PersistenceMode.Postgres`) would:
 *  1. Add a new `case` to [[chess.config.PersistenceMode]].
 *  2. Add a new `case` branch in [[assemble]].
 *  3. Implement the corresponding private assembly method here with JDBC/Slick/Doobie.
 *  Application services, routes, and GUI/TUI adapters are unaffected.
 */
object PersistenceAssembly:

  /** Assemble repository infrastructure according to `config.persistence`.
   *
   *  Delegates to the strategy-specific assembly method.  The returned
   *  [[PersistenceWiring]] carries the repositories needed by the application
   *  service layer.
   */
  def assemble(config: AppConfig): PersistenceWiring =
    config.persistence match
      case PersistenceMode.InMemory => assembleInMemory()

  // ── Strategy: InMemory ──────────────────────────────────────────────────────

  /** All state in JVM heap; zero external dependencies. */
  private def assembleInMemory(): PersistenceWiring =
    val sessionRepo = InMemorySessionRepository()
    val gameRepo    = InMemoryGameRepository()
    val store       = InMemorySessionGameStore(sessionRepo, gameRepo)
    PersistenceWiring(sessionRepo, gameRepo, store)
