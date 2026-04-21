package chess.startup.local

enum LocalPersistenceMode:
  case InMemory
  case SQLite

final case class LocalSqliteConfig(path: String)

/** Minimal runtime config for standalone local clients such as GUI and TUI. */
final case class LocalRuntimeConfig(
<<<<<<< HEAD
    persistence: LocalPersistenceMode,
    sqlite: Option[LocalSqliteConfig]
=======
  persistence: LocalPersistenceMode,
  sqlite:      Option[LocalSqliteConfig]
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
)
