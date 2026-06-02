package chess.server.assembly

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
import chess.adapter.repository.postgres.PostgresPersistenceRuntime
import chess.application.port.repository.{GameRepository, SessionGameStore, SessionRepository}
import chess.server.config.{AppConfig, MongoConfig, PersistenceMode, PostgresConfig, SqliteConfig}
import chess.server.persistence.MongoPersistenceRuntime
<<<<<<< HEAD
=======
import slick.jdbc.PostgresProfile.api.Database
>>>>>>> 2b1aa125 (real migration ok)

final case class PersistenceWiring(
    sessionRepository: SessionRepository,
    gameRepository: GameRepository,
    store: SessionGameStore,
    shutdown: () => Unit = () => ()
=======
import chess.adapter.repository.{InMemoryGameRepository, InMemorySessionGameStore, InMemorySessionRepository}
import chess.adapter.repository.sqlite.{SqliteDataSource, SqliteGameRepository, SqliteSchema, SqliteSessionGameStore, SqliteSessionRepository}
import chess.application.port.repository.{GameRepository, SessionGameStore, SessionRepository}
import chess.server.config.{AppConfig, PersistenceMode, SqliteConfig}

final case class PersistenceWiring(
  sessionRepository: SessionRepository,
  gameRepository:    GameRepository,
  store:             SessionGameStore
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
)

/** Game Service persistence infrastructure assembly. */
object PersistenceAssembly:

  def assemble(config: AppConfig): PersistenceWiring =
    config.persistence match
<<<<<<< HEAD
      case PersistenceMode.Postgres =>
        assemblePostgres(
          config.postgres.getOrElse(
            throw IllegalArgumentException(
              "Postgres persistence mode selected but postgres config is missing"
            )
          )
        )
      case PersistenceMode.Mongo =>
        assembleMongo(
          config.mongo.getOrElse(
            throw IllegalArgumentException(
              "Mongo persistence mode selected but mongo config is missing"
            )
          )
        )
      case PersistenceMode.InMemory => assembleInMemory()
      case PersistenceMode.SQLite =>
        assembleSQLite(
          config.sqlite.getOrElse(
            throw IllegalArgumentException(
              "SQLite persistence mode selected but sqlite config is missing"
            )
          )
        )

  private def assembleInMemory(): PersistenceWiring =
    val sessionRepo = InMemorySessionRepository()
    val gameRepo = InMemoryGameRepository()
    val store = InMemorySessionGameStore(sessionRepo, gameRepo)
=======
      case PersistenceMode.InMemory => assembleInMemory()
      case PersistenceMode.SQLite   => assembleSQLite(config.sqlite.get)

  private def assembleInMemory(): PersistenceWiring =
    val sessionRepo = InMemorySessionRepository()
    val gameRepo    = InMemoryGameRepository()
    val store       = InMemorySessionGameStore(sessionRepo, gameRepo)
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
    PersistenceWiring(sessionRepo, gameRepo, store)

  private def assembleSQLite(cfg: SqliteConfig): PersistenceWiring =
    val ds = SqliteDataSource(cfg.path)
    ds.withConnection(SqliteSchema.createTables)
    val sessionRepo = SqliteSessionRepository(ds)
<<<<<<< HEAD
    val gameRepo = SqliteGameRepository(ds)
    val store = SqliteSessionGameStore(ds, sessionRepo, gameRepo)
    PersistenceWiring(sessionRepo, gameRepo, store)

  private def assemblePostgres(cfg: PostgresConfig): PersistenceWiring =
<<<<<<< HEAD
<<<<<<< HEAD
=======
>>>>>>> 966317ea (added bot container)
    PostgresPersistenceRuntime.open(cfg.url, cfg.user, cfg.password, schema = cfg.schema) match
      case Left(error) => throw IllegalArgumentException(s"Postgres persistence initialization failed: $error")
      case Right(runtime) =>
        PersistenceWiring(
          runtime.sessionRepository,
          runtime.gameRepository,
          runtime.store,
          shutdown = runtime.close
        )
=======
    PostgresFlywaySchemaInitializer.migrate(
      url = cfg.url,
      user = cfg.user,
      password = cfg.password
    )
    val db =
      Database.forURL(
        url = cfg.url,
        user = cfg.user,
        password = cfg.password,
        driver = "org.postgresql.Driver"
      )
    val sessionRepo = PostgresSessionRepository(db)
    val gameRepo = PostgresGameRepository(db)
    val store = PostgresSessionGameStore(db)
    PersistenceWiring(sessionRepo, gameRepo, store, shutdown = () => db.close())
>>>>>>> 2b1aa125 (real migration ok)

  private def assembleMongo(cfg: MongoConfig): PersistenceWiring =
    MongoPersistenceRuntime.open(cfg) match
      case Left(error) => throw IllegalArgumentException(s"Mongo persistence initialization failed: $error")
      case Right(runtime) =>
        PersistenceWiring(
          runtime.sessionRepository,
          runtime.gameRepository,
          runtime.store,
          shutdown = () => runtime.close()
        )
<<<<<<< HEAD
=======
    val gameRepo    = SqliteGameRepository(ds)
    val store       = SqliteSessionGameStore(ds, sessionRepo, gameRepo)
    PersistenceWiring(sessionRepo, gameRepo, store)
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
=======
>>>>>>> 2b1aa125 (real migration ok)
