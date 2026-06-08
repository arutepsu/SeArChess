package chess.server.assembly

import chess.adapter.repository.{
  InMemoryGameRepository,
  InMemorySessionGameStore,
  InMemorySessionRepository
}
import chess.adapter.repository.sqlite.{
  SqliteDataSource,
  SqliteExternalGameBindingRepository,
  SqliteExternalGameCommandStore,
  SqliteGameRepository,
  SqliteSchema,
  SqliteSessionGameStore,
  SqliteSessionRepository
}
import chess.adapter.repository.postgres.PostgresPersistenceRuntime
import chess.application.bot.BotTurnTaskRepository
import chess.application.port.repository.{
  ExternalGameBindingRepository,
  ExternalGameCommandStore,
  GameRepository,
  SessionGameStore,
  SessionRepository
}
import chess.server.config.{AppConfig, MongoConfig, PersistenceMode, PostgresConfig, SqliteConfig}
import chess.server.persistence.MongoPersistenceRuntime

final case class PersistenceWiring(
    sessionRepository: SessionRepository,
    gameRepository: GameRepository,
    store: SessionGameStore,
    externalGameBindingRepository: Option[ExternalGameBindingRepository] = None,
    externalGameCommandStore: Option[ExternalGameCommandStore] = None,
    botTurnTaskRepository: Option[BotTurnTaskRepository] = None,
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
    val bindingRepo = SqliteExternalGameBindingRepository(ds)
    val commandStore = SqliteExternalGameCommandStore(ds, sessionRepo, gameRepo, bindingRepo)
    PersistenceWiring(
      sessionRepo,
      gameRepo,
      store,
      externalGameBindingRepository = Some(bindingRepo),
      externalGameCommandStore = Some(commandStore)
    )

  private def assemblePostgres(cfg: PostgresConfig): PersistenceWiring =
    PostgresPersistenceRuntime.open(cfg.url, cfg.user, cfg.password, schema = cfg.schema) match
      case Left(error) => throw IllegalArgumentException(s"Postgres persistence initialization failed: $error")
      case Right(runtime) =>
        PersistenceWiring(
          runtime.sessionRepository,
          runtime.gameRepository,
          runtime.store,
          externalGameBindingRepository = Some(runtime.externalGameBindingRepository),
          externalGameCommandStore = Some(runtime.externalGameCommandStore),
          botTurnTaskRepository = Some(runtime.botTurnTaskRepository),
          shutdown = runtime.close
        )

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
