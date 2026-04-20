package chess.adapter.repository.sqlite

import chess.application.port.repository.RepositoryError

import java.sql.{Connection, DriverManager}

/** Single shared JDBC connection to a SQLite database file.
 *
 *  All access is serialized through the connection monitor.  SQLite in
 *  WAL mode can handle concurrent reads but the single-connection model
 *  is sufficient for the embedded single-JVM use case and avoids
 *  connection-pool complexity.
 *
 *  Call [[close]] when the application shuts down to release the file lock.
 */
class SqliteDataSource(path: String):

  private val conn: Connection =
    val c = DriverManager.getConnection(s"jdbc:sqlite:$path")
    c.setAutoCommit(true)
    c

  def withConnection[A](f: Connection => A): A =
    conn.synchronized(f(conn))

  def withTransaction[A](f: Connection => Either[RepositoryError, A]): Either[RepositoryError, A] =
    conn.synchronized {
      conn.setAutoCommit(false)
      try
        val result = f(conn)
        result match
          case Right(_) => conn.commit()
          case Left(_)  => conn.rollback()
        conn.setAutoCommit(true)
        result
      catch
        case e: java.sql.SQLException =>
          scala.util.Try(conn.rollback())
          conn.setAutoCommit(true)
          Left(RepositoryError.StorageFailure(e.getMessage))
    }

  def close(): Unit = conn.synchronized(conn.close())
