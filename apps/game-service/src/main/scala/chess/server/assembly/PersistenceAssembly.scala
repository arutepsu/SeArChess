package chess.server.assembly

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
import chess.adapter.repository.postgres.{
  PostgresFlywaySchemaInitializer,
  PostgresGameRepository,
  PostgresSessionGameStore,
  PostgresSessionRepository
}
import chess.application.port.repository.{GameRepository, SessionGameStore, SessionRepository}
import chess.server.config.{AppConfig, MongoConfig, PersistenceMode, PostgresConfig, SqliteConfig}
import chess.server.persistence.MongoPersistenceRuntime
import slick.jdbc.PostgresProfile.api.Database

final case class PersistenceWiring(
    sessionRepository: SessionRepository,
    gameRepository: GameRepository,
    store: SessionGameStore,
    shutdown: () => Unit = () => ()
)

/** Game Service persistence infrastructure assembly. */
object PersistenceAssembly:

  def assemble(config: AppConfig): PersistenceWiring =
    config.persistence match
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
    PersistenceWiring(sessionRepo, gameRepo, store)

  private def assembleSQLite(cfg: SqliteConfig): PersistenceWiring =
    val ds = SqliteDataSource(cfg.path)
    ds.withConnection(SqliteSchema.createTables)
    val sessionRepo = SqliteSessionRepository(ds)
    val gameRepo = SqliteGameRepository(ds)
    val store = SqliteSessionGameStore(ds, sessionRepo, gameRepo)
    PersistenceWiring(sessionRepo, gameRepo, store)

  private def assemblePostgres(cfg: PostgresConfig): PersistenceWiring =
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
