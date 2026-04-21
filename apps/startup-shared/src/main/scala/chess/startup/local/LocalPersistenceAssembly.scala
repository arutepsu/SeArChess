package chess.startup.local

import chess.adapter.repository.{InMemoryGameRepository, InMemorySessionGameStore, InMemorySessionRepository}
import chess.adapter.repository.sqlite.{SqliteDataSource, SqliteGameRepository, SqliteSchema, SqliteSessionGameStore, SqliteSessionRepository}
import chess.application.port.repository.{GameRepository, SessionGameStore, SessionRepository}

final case class LocalPersistenceWiring(
  sessionRepository: SessionRepository,
  gameRepository:    GameRepository,
  store:             SessionGameStore
)

/** Local GUI/TUI persistence assembly. Does not configure service runtime. */
object LocalPersistenceAssembly:

  def assemble(config: LocalRuntimeConfig): LocalPersistenceWiring =
    config.persistence match
      case LocalPersistenceMode.InMemory => assembleInMemory()
      case LocalPersistenceMode.SQLite   => assembleSQLite(config.sqlite.get)

  private def assembleInMemory(): LocalPersistenceWiring =
    val sessionRepo = InMemorySessionRepository()
    val gameRepo    = InMemoryGameRepository()
    val store       = InMemorySessionGameStore(sessionRepo, gameRepo)
    LocalPersistenceWiring(sessionRepo, gameRepo, store)

  private def assembleSQLite(cfg: LocalSqliteConfig): LocalPersistenceWiring =
    val ds = SqliteDataSource(cfg.path)
    ds.withConnection(SqliteSchema.createTables)
    val sessionRepo = SqliteSessionRepository(ds)
    val gameRepo    = SqliteGameRepository(ds)
    val store       = SqliteSessionGameStore(ds, sessionRepo, gameRepo)
    LocalPersistenceWiring(sessionRepo, gameRepo, store)
