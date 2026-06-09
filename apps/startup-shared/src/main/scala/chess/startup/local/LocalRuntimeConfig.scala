package chess.startup.local

enum LocalPersistenceMode:
  case InMemory
  case SQLite

final case class LocalSqliteConfig(path: String)

/** Minimal runtime config for standalone local clients such as GUI and TUI. */
final case class LocalRuntimeConfig(
    persistence: LocalPersistenceMode,
    sqlite: Option[LocalSqliteConfig]
)
