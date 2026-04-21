package chess.startup.local

/** Loads only the local-client settings shared by GUI and TUI startup. */
object LocalRuntimeConfigLoader:

  private val DefaultPersistence = "in-memory"
  private val DefaultSqlitePath = "chess.db"

  def load(): Either[String, LocalRuntimeConfig] =
    loadFrom(key => Option(System.getenv(key)).filter(_.nonEmpty))

  private[local] def loadFrom(env: String => Option[String]): Either[String, LocalRuntimeConfig] =
    for
      persistence <- parsePersistenceMode(env("PERSISTENCE_MODE").getOrElse(DefaultPersistence))
      sqlitePath = env("CHESS_DB_PATH").getOrElse(DefaultSqlitePath)
    yield LocalRuntimeConfig(
      persistence = persistence,
      sqlite =
        if persistence == LocalPersistenceMode.SQLite then Some(LocalSqliteConfig(sqlitePath))
        else None
    )

  def loadOrExit(appName: String): LocalRuntimeConfig =
    load().fold(
      err => { System.err.println(s"[$appName] Configuration error: $err"); sys.exit(1) },
      identity
    )

  private def parsePersistenceMode(value: String): Either[String, LocalPersistenceMode] =
    value.toLowerCase match
      case "in-memory" | "inmemory" => Right(LocalPersistenceMode.InMemory)
      case "sqlite"                 => Right(LocalPersistenceMode.SQLite)
      case _ => Left(s"PERSISTENCE_MODE must be 'in-memory' or 'sqlite', got: '$value'")
