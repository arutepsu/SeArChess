package chess.adapter.repository.sqlite

import java.sql.Connection

/** DDL for the SQLite schema used by the persistence adapters.
  *
  * All tables use `CREATE TABLE IF NOT EXISTS` so [[createTables]] is safe to call on every startup
  * without a migrations framework.
  *
  * `history_event_outbox` is included here so the table exists whenever
  * [[SqliteSessionGameStore.saveTerminal]] or [[SqliteSessionRepository.saveCancelWithOutbox]]
  * perform a transactional outbox write. [[chess.adapter.event.SqliteHistoryEventOutbox]] also
  * creates the table independently in its own `initialize()` call so it continues to work in
  * isolation (e.g. tests that instantiate it directly without running the full schema).
  */
object SqliteSchema:

  private val createSessions =
    """CREATE TABLE IF NOT EXISTS sessions (
      |  session_id       TEXT PRIMARY KEY,
      |  game_id          TEXT NOT NULL UNIQUE,
      |  mode             TEXT NOT NULL,
      |  white_controller TEXT NOT NULL,
      |  black_controller TEXT NOT NULL,
      |  lifecycle        TEXT NOT NULL,
      |  created_at       TEXT NOT NULL,
      |  updated_at       TEXT NOT NULL
      |)""".stripMargin

  private val createGameStates =
    """CREATE TABLE IF NOT EXISTS game_states (
      |  game_id    TEXT PRIMARY KEY,
      |  state_json TEXT NOT NULL
      |)""".stripMargin

  private val createExternalGameBindings =
    """CREATE TABLE IF NOT EXISTS external_game_bindings (
      |  binding_id         TEXT    PRIMARY KEY,
      |  platform           TEXT    NOT NULL,
      |  external_game_id   TEXT    NOT NULL,
      |  internal_game_id   TEXT    NOT NULL,
      |  session_id         TEXT    NOT NULL,
      |  our_actor_id       TEXT    NOT NULL,
      |  status             TEXT    NOT NULL,
      |  status_reason      TEXT,
      |  last_processed_ply INTEGER NOT NULL DEFAULT 0,
      |  created_at         TEXT    NOT NULL,
      |  updated_at         TEXT    NOT NULL,
      |  UNIQUE (platform, external_game_id)
      |)""".stripMargin

  private val createExternalGameBindingsIndex =
    """CREATE INDEX IF NOT EXISTS external_bindings_status_idx
      |ON external_game_bindings(status)""".stripMargin

  private val createHistoryEventOutbox =
    """CREATE TABLE IF NOT EXISTS history_event_outbox (
      |  id           INTEGER PRIMARY KEY AUTOINCREMENT,
      |  event_type   TEXT NOT NULL,
      |  session_id   TEXT NOT NULL,
      |  game_id      TEXT NOT NULL,
      |  payload_json TEXT NOT NULL,
      |  created_at   TEXT NOT NULL,
      |  attempts     INTEGER NOT NULL DEFAULT 0,
      |  last_attempted_at TEXT,
      |  last_error   TEXT,
      |  delivered_at TEXT
      |)""".stripMargin

  private val createHistoryEventOutboxIndex =
    """CREATE INDEX IF NOT EXISTS idx_history_event_outbox_pending
      |ON history_event_outbox(delivered_at, id)""".stripMargin

  def createTables(conn: Connection): Unit =
    val stmt = conn.createStatement()
    try
      stmt.execute(createSessions)
      stmt.execute(createGameStates)
      stmt.execute(createExternalGameBindings)
      stmt.execute(createExternalGameBindingsIndex)
      stmt.execute(createHistoryEventOutbox)
      stmt.execute(createHistoryEventOutboxIndex)
    finally stmt.close()
