package chess.server.assembly

import chess.adapter.repository.{InMemoryGameRepository, InMemorySessionGameStore, InMemorySessionRepository}
import chess.adapter.repository.sqlite.{SqliteDataSource, SqliteGameRepository, SqliteSchema, SqliteSessionGameStore, SqliteSessionRepository}
import chess.application.port.repository.{GameRepository, SessionGameStore, SessionRepository}
import chess.server.config.{AppConfig, PersistenceMode, SqliteConfig}

final case class PersistenceWiring(
  sessionRepository: SessionRepository,
  gameRepository:    GameRepository,
  store:             SessionGameStore
)

/** Game Service persistence infrastructure assembly. */
object PersistenceAssembly:

  def assemble(config: AppConfig): PersistenceWiring =
    config.persistence match
      case PersistenceMode.InMemory => assembleInMemory()
      case PersistenceMode.SQLite   => assembleSQLite(config.sqlite.get)

  private def assembleInMemory(): PersistenceWiring =
    val sessionRepo = InMemorySessionRepository()
    val gameRepo    = InMemoryGameRepository()
    val store       = InMemorySessionGameStore(sessionRepo, gameRepo)
    PersistenceWiring(sessionRepo, gameRepo, store)

  private def assembleSQLite(cfg: SqliteConfig): PersistenceWiring =
    val ds = SqliteDataSource(cfg.path)
    ds.withConnection(SqliteSchema.createTables)
    val sessionRepo = SqliteSessionRepository(ds)
    val gameRepo    = SqliteGameRepository(ds)
    val store       = SqliteSessionGameStore(ds, sessionRepo, gameRepo)
    PersistenceWiring(sessionRepo, gameRepo, store)
