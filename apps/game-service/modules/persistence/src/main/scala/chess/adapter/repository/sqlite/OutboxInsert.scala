package chess.adapter.repository.sqlite

import chess.application.port.repository.RepositoryError

import java.sql.Connection
import java.time.Instant
import scala.util.control.NonFatal

/** Inserts one row into `history_event_outbox` using a caller-supplied connection.
  *
  * The connection must already be inside an open JDBC transaction managed by the caller (e.g.
  * [[SqliteDataSource.withTransaction]]). This object performs only the INSERT; it does not open,
  * commit, or roll back the transaction.
  *
  * Both [[SqliteSessionGameStore.saveTerminal]] and
  * [[SqliteSessionRepository.saveCancelWithOutbox]] use this to include the outbox write inside the
  * same transaction as their game-state / session writes, closing the consistency gap described in
  * `docs/architecture/game-history-outbox.md`.
  *
  * The DDL for `history_event_outbox` mirrors [[SqliteHistoryEventOutbox]] and is also included in
  * [[SqliteSchema.createTables]] so the table exists whenever the full persistence schema is
  * initialised.
  */
private[sqlite] object OutboxInsert:

  /** Insert `payloadJson` into `history_event_outbox` using `conn`.
    *
    * Extracts `type`, `sessionId`, and `gameId` from the JSON payload.
    *
    * @return
    *   [[Right]] on success; [[Left]] with a [[RepositoryError.StorageFailure]] if the INSERT fails
    *   (SQL error or malformed payload)
    */
  def apply(conn: Connection, payloadJson: String): Either[RepositoryError, Unit] =
    try
      val obj = ujson.read(payloadJson).obj
      val eventType = obj("type").str
      val sessionId = obj("sessionId").str
      val gameId = obj("gameId").str
      val ps = conn.prepareStatement(
        """INSERT INTO history_event_outbox
          |(event_type, session_id, game_id, payload_json, created_at, attempts)
          |VALUES (?, ?, ?, ?, ?, 0)""".stripMargin
      )
      try
        ps.setString(1, eventType)
        ps.setString(2, sessionId)
        ps.setString(3, gameId)
        ps.setString(4, payloadJson)
        ps.setString(5, Instant.now().toString)
        ps.executeUpdate()
        Right(())
      finally ps.close()
    catch
      case e: java.sql.SQLException => Left(RepositoryError.StorageFailure(e.getMessage))
      case NonFatal(e) =>
        Left(RepositoryError.StorageFailure(s"outbox payload error: ${e.getMessage}"))
