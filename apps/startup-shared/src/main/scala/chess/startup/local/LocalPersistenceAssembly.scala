package chess.startup.local

<<<<<<< HEAD
import chess.adapter.repository.{
  InMemoryGameRepository,
  InMemorySessionGameStore,
  InMemorySessionRepository
}
import chess.adapter.repository.sqlite.{
  SqliteDataSource,
  SqliteGameRepository,
  SqliteSchema,
  SqliteSessionGameStore,
  SqliteSessionRepository
}
import chess.application.port.repository.{GameRepository, SessionGameStore, SessionRepository}

final case class LocalPersistenceWiring(
    sessionRepository: SessionRepository,
    gameRepository: GameRepository,
    store: SessionGameStore
=======
import chess.adapter.repository.{InMemoryGameRepository, InMemorySessionGameStore, InMemorySessionRepository}
import chess.adapter.repository.sqlite.{SqliteDataSource, SqliteGameRepository, SqliteSchema, SqliteSessionGameStore, SqliteSessionRepository}
import chess.application.port.repository.{GameRepository, SessionGameStore, SessionRepository}

final case class LocalPersistenceWiring(
  sessionRepository: SessionRepository,
  gameRepository:    GameRepository,
  store:             SessionGameStore
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
)

/** Local GUI/TUI persistence assembly. Does not configure service runtime. */
object LocalPersistenceAssembly:

  def assemble(config: LocalRuntimeConfig): LocalPersistenceWiring =
    config.persistence match
      case LocalPersistenceMode.InMemory => assembleInMemory()
<<<<<<< HEAD
      case LocalPersistenceMode.SQLite =>
        assembleSQLite(
          config.sqlite.getOrElse(
            throw IllegalArgumentException(
              "SQLite persistence mode selected but no sqlite config provided"
            )
          )
        )

  private def assembleInMemory(): LocalPersistenceWiring =
    val sessionRepo = InMemorySessionRepository()
    val gameRepo = InMemoryGameRepository()
    val store = InMemorySessionGameStore(sessionRepo, gameRepo)
=======
      case LocalPersistenceMode.SQLite   => assembleSQLite(config.sqlite.get)

  private def assembleInMemory(): LocalPersistenceWiring =
    val sessionRepo = InMemorySessionRepository()
    val gameRepo    = InMemoryGameRepository()
    val store       = InMemorySessionGameStore(sessionRepo, gameRepo)
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
    LocalPersistenceWiring(sessionRepo, gameRepo, store)

  private def assembleSQLite(cfg: LocalSqliteConfig): LocalPersistenceWiring =
    val ds = SqliteDataSource(cfg.path)
    ds.withConnection(SqliteSchema.createTables)
    val sessionRepo = SqliteSessionRepository(ds)
<<<<<<< HEAD
    val gameRepo = SqliteGameRepository(ds)
    val store = SqliteSessionGameStore(ds, sessionRepo, gameRepo)
=======
    val gameRepo    = SqliteGameRepository(ds)
    val store       = SqliteSessionGameStore(ds, sessionRepo, gameRepo)
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
    LocalPersistenceWiring(sessionRepo, gameRepo, store)
