package chess.adapter.repository.sqlite

import java.sql.Connection

/** DDL for the SQLite schema used by the persistence adapters.
 *
 *  Both tables use `CREATE TABLE IF NOT EXISTS` so [[createTables]] is safe to
 *  call on every startup without a migrations framework.
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

  def createTables(conn: Connection): Unit =
    val stmt = conn.createStatement()
    try
      stmt.execute(createSessions)
      stmt.execute(createGameStates)
    finally
      stmt.close()
