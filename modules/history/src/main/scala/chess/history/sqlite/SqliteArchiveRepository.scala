package chess.history.sqlite

import chess.application.session.model.SessionIds.GameId
import chess.history.{ArchiveRecord, ArchiveRecordJson, ArchiveRepository, ArchiveRepositoryError}
import java.sql.{Connection, DriverManager}
import java.util.UUID
import scala.util.control.NonFatal

class SqliteArchiveRepository(path: String) extends ArchiveRepository:

  private val conn: Connection =
    val c = DriverManager.getConnection(s"jdbc:sqlite:$path")
    c.setAutoCommit(true)
    createTable(c)
    c

  override def upsert(record: ArchiveRecord): Either[ArchiveRepositoryError, Unit] =
    withConnection { c =>
      val sql =
        """INSERT INTO history_archives (game_id, session_id, record_json, created_at, closed_at, materialized_at)
          |VALUES (?, ?, ?, ?, ?, ?)
          |ON CONFLICT(game_id) DO UPDATE SET
          |  session_id = excluded.session_id,
          |  record_json = excluded.record_json,
          |  created_at = excluded.created_at,
          |  closed_at = excluded.closed_at,
          |  materialized_at = excluded.materialized_at
          |""".stripMargin
      val ps = c.prepareStatement(sql)
      try
        ps.setString(1, record.gameId.value.toString)
        ps.setString(2, record.sessionId.value.toString)
        ps.setString(3, ujson.write(ArchiveRecordJson.toJson(record)))
        ps.setString(4, record.createdAt.toString)
        ps.setString(5, record.closedAt.toString)
        ps.setString(6, record.materializedAt.toString)
        ps.executeUpdate()
        Right(())
      finally ps.close()
    }

  override def findByGameId(gameId: GameId): Either[ArchiveRepositoryError, Option[ArchiveRecord]] =
    findRecordJson(gameId).flatMap {
      case None => Right(None)
      case Some(json) =>
        ArchiveRecordJson.fromJson(json)
          .left.map(ArchiveRepositoryError.StorageFailure(_))
          .map(Some(_))
    }

  def findRecordJson(gameId: GameId): Either[ArchiveRepositoryError, Option[ujson.Value]] =
    withConnection { c =>
      val ps = c.prepareStatement("SELECT record_json FROM history_archives WHERE game_id = ?")
      try
        ps.setString(1, gameId.value.toString)
        val rs = ps.executeQuery()
        try
          if rs.next() then Right(Some(ujson.read(rs.getString("record_json"))))
          else Right(None)
        finally rs.close()
      finally ps.close()
    }

  def close(): Unit = conn.synchronized(conn.close())

  private def withConnection[A](f: Connection => Either[ArchiveRepositoryError, A]): Either[ArchiveRepositoryError, A] =
    conn.synchronized {
      try f(conn)
      catch case NonFatal(e) => Left(ArchiveRepositoryError.StorageFailure(e.getMessage))
    }

  private def createTable(c: Connection): Unit =
    val stmt = c.createStatement()
    try
      stmt.execute(
        """CREATE TABLE IF NOT EXISTS history_archives (
          |  game_id         TEXT PRIMARY KEY,
          |  session_id      TEXT NOT NULL,
          |  record_json     TEXT NOT NULL,
          |  created_at      TEXT NOT NULL,
          |  closed_at       TEXT NOT NULL,
          |  materialized_at TEXT NOT NULL
          |)""".stripMargin
      )
    finally stmt.close()
