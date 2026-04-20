package chess.adapter.repository.sqlite

import chess.application.port.repository.{RepositoryError, SessionGameStore}
import chess.application.session.model.GameSession
import chess.domain.state.GameState

/** SQLite-backed [[SessionGameStore]].
 *
 *  Wraps both the session upsert and the game-state upsert in a single
 *  transaction so that a partial write is never visible — either both
 *  records land or neither does.
 *
 *  [[saveTerminal]] extends this guarantee to include a pre-serialised outbox
 *  payload.  All three writes — `sessions`, `game_states`, and
 *  `history_event_outbox` — share one JDBC transaction.  If the outbox insert
 *  fails the session and game-state writes are also rolled back, closing the
 *  consistency gap described in `docs/architecture/game-history-outbox.md`.
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

  /** Persist session + game state + outbox payloads in one JDBC transaction.
   *
   *  Delegates to [[save]] when `outboxPayloads` is empty (non-terminal writes
   *  and in-memory fallback — no outbox row needed).
   *
   *  When `outboxPayloads` is non-empty, the full transactional path is used:
   *  session and game-state rows are written first; if either fails the whole
   *  transaction is rolled back.  [[OutboxInsert]] is then called for each
   *  payload; if any insert fails the transaction is also rolled back.
   */
  override def saveTerminal(
    session:        GameSession,
    state:          GameState,
    outboxPayloads: List[String]
  ): Either[RepositoryError, Unit] =
    if outboxPayloads.isEmpty then save(session, state)
    else
      ds.withTransaction { conn =>
        val stateResult =
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
        stateResult.flatMap { _ =>
          outboxPayloads.foldLeft[Either[RepositoryError, Unit]](Right(())) { (acc, payload) =>
            acc.flatMap(_ => OutboxInsert(conn, payload))
          }
        }
      }
