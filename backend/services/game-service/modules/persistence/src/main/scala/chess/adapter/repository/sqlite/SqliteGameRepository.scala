package chess.adapter.repository.sqlite

import chess.application.port.repository.{GameRepository, RepositoryError}
import chess.application.session.model.SessionIds.GameId
import chess.domain.state.GameState

/** SQLite-backed [[GameRepository]].
  *
  * Game state is stored as a JSON blob in the `game_states` table. All reads and writes go through
  * [[SqliteDataSource.withConnection]]; transactional multi-table writes are handled at the
  * [[SqliteSessionGameStore]] level instead.
  */
class SqliteGameRepository(ds: SqliteDataSource) extends GameRepository:

  override def load(gameId: GameId): Either[RepositoryError, GameState] =
    ds.withConnection { conn =>
      val ps = conn.prepareStatement("SELECT state_json FROM game_states WHERE game_id = ?")
      try
        ps.setString(1, gameId.value.toString)
        val rs = ps.executeQuery()
        try
          if rs.next() then GameStateJson.decode(rs.getString("state_json"))
          else Left(RepositoryError.NotFound(gameId.value.toString))
        finally
          rs.close()
      finally ps.close()
    }

  override def save(gameId: GameId, state: GameState): Either[RepositoryError, Unit] =
    try
      ds.withConnection { conn =>
        upsertState(conn, gameId, state)
        Right(())
      }
    catch
      case e: java.sql.SQLException =>
        Left(RepositoryError.StorageFailure(e.getMessage))

  /** Write a game state row within a caller-supplied JDBC connection.
    *
    * The connection must already be inside an open transaction managed by the caller
    * (e.g. [[SqliteDataSource.withTransaction]]). This method performs only the INSERT OR REPLACE;
    * it does not open, commit, or roll back the transaction.
    *
    * Used by [[SqliteExternalGameCommandStore]] to include the game-state write inside the same
    * transaction as session and binding writes.
    */
  private[sqlite] def upsertState(conn: java.sql.Connection, gameId: GameId, state: GameState): Unit =
    val ps = conn.prepareStatement(
      "INSERT OR REPLACE INTO game_states (game_id, state_json) VALUES (?, ?)"
    )
    try
      ps.setString(1, gameId.value.toString)
      ps.setString(2, GameStateJson.encode(state))
      ps.executeUpdate()
    finally ps.close()
