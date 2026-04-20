package chess.adapter.repository.sqlite

import chess.application.port.repository.{RepositoryError, SessionGameStore}
import chess.application.session.model.GameSession
import chess.domain.state.GameState

/** SQLite-backed [[SessionGameStore]].
 *
 *  Wraps both the session upsert and the game-state upsert in a single
 *  transaction so that a partial write is never visible — either both
 *  records land or neither does.
 */
class SqliteSessionGameStore(
  ds:          SqliteDataSource,
  sessionRepo: SqliteSessionRepository,
  gameRepo:    SqliteGameRepository
) extends SessionGameStore:

  override def save(session: GameSession, state: GameState): Either[RepositoryError, Unit] =
    ds.withTransaction { conn =>
      try
        sessionRepo.upsertSession(conn, session)
        val ps = conn.prepareStatement(
          "INSERT OR REPLACE INTO game_states (game_id, state_json) VALUES (?, ?)"
        )
        try
          ps.setString(1, session.gameId.value.toString)
          ps.setString(2, GameStateJson.encode(state))
          ps.executeUpdate()
        finally
          ps.close()
        Right(())
      catch
        case e: java.sql.SQLException =>
          Left(RepositoryError.StorageFailure(e.getMessage))
    }
