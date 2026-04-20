package chess.adapter.event

import java.sql.{Connection, DriverManager, ResultSet}
import java.time.Instant
import scala.util.control.NonFatal

class SqliteHistoryEventOutbox(path: String) extends HistoryEventOutbox:

  Class.forName("org.sqlite.JDBC")

  private val conn: Connection =
    val c = DriverManager.getConnection(s"jdbc:sqlite:$path")
    c.setAutoCommit(true)
    c

  initialize()

  override def append(payloadJson: String): Either[String, Long] =
    metadata(payloadJson).flatMap { case (eventType, sessionId, gameId) =>
      synchronized:
        try
          val sql =
            """INSERT INTO history_event_outbox
              |(event_type, session_id, game_id, payload_json, created_at, attempts)
              |VALUES (?, ?, ?, ?, ?, 0)""".stripMargin
          val stmt = conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)
          try
            stmt.setString(1, eventType)
            stmt.setString(2, sessionId)
            stmt.setString(3, gameId)
            stmt.setString(4, payloadJson)
            stmt.setString(5, Instant.now().toString)
            stmt.executeUpdate()
            val keys = stmt.getGeneratedKeys
            try
              if keys.next() then Right(keys.getLong(1))
              else Left("SQLite outbox insert did not return a generated id")
            finally keys.close()
          finally stmt.close()
        catch case NonFatal(e) => Left(e.getMessage)
    }

  override def summary(): Either[String, HistoryOutboxSummary] =
    allEntries().map { entries =>
      val pending = entries.filter(_.deliveredAt.isEmpty)
      HistoryOutboxSummary(
        totalCount      = entries.size,
        pendingCount    = pending.size,
        deliveredCount  = entries.count(_.deliveredAt.isDefined),
        retryingCount   = pending.count(_.attempts > 0),
        oldestPendingAt = pending.map(_.createdAt).sortBy(_.toEpochMilli).headOption,
        newestPendingAt = pending.map(_.createdAt).sortBy(_.toEpochMilli).lastOption,
        pendingByType   = pending.groupBy(_.eventType).view.mapValues(_.size).toMap
      )
    }

  override def pending(limit: Int): Either[String, List[HistoryOutboxEntry]] =
    synchronized:
      try
        val stmt = conn.prepareStatement(
          """SELECT id, event_type, session_id, game_id, payload_json, created_at,
            |       attempts, last_error, delivered_at
            |FROM history_event_outbox
            |WHERE delivered_at IS NULL
            |ORDER BY id ASC
            |LIMIT ?""".stripMargin
        )
        try
          stmt.setInt(1, limit.max(1))
          val rs = stmt.executeQuery()
          try
            val b = List.newBuilder[HistoryOutboxEntry]
            while rs.next() do b += entry(rs)
            Right(b.result())
          finally rs.close()
        finally stmt.close()
      catch case NonFatal(e) => Left(e.getMessage)

  override def find(id: Long): Either[String, Option[HistoryOutboxEntry]] =
    synchronized:
      try
        val stmt = conn.prepareStatement(
          """SELECT id, event_type, session_id, game_id, payload_json, created_at,
            |       attempts, last_error, delivered_at
            |FROM history_event_outbox
            |WHERE id = ?""".stripMargin
        )
        try
          stmt.setLong(1, id)
          val rs = stmt.executeQuery()
          try
            if rs.next() then Right(Some(entry(rs)))
            else Right(None)
          finally rs.close()
        finally stmt.close()
      catch case NonFatal(e) => Left(e.getMessage)

  override def markDelivered(id: Long): Either[String, Unit] =
    synchronized:
      update(
        """UPDATE history_event_outbox
          |SET delivered_at = ?, last_error = NULL
          |WHERE id = ?""".stripMargin,
        _.setString(1, Instant.now().toString),
        id
      )

  override def markFailed(id: Long, error: String): Either[String, Unit] =
    synchronized:
      update(
        """UPDATE history_event_outbox
          |SET attempts = attempts + 1, last_error = ?
          |WHERE id = ?""".stripMargin,
        _.setString(1, error.take(1000)),
        id
      )

  def close(): Unit = synchronized(conn.close())

  private def initialize(): Unit =
    synchronized:
      val stmt = conn.createStatement()
      try
        stmt.execute(
          """CREATE TABLE IF NOT EXISTS history_event_outbox (
            |  id           INTEGER PRIMARY KEY AUTOINCREMENT,
            |  event_type   TEXT NOT NULL,
            |  session_id   TEXT NOT NULL,
            |  game_id      TEXT NOT NULL,
            |  payload_json TEXT NOT NULL,
            |  created_at   TEXT NOT NULL,
            |  attempts     INTEGER NOT NULL DEFAULT 0,
            |  last_error   TEXT,
            |  delivered_at TEXT
            |)""".stripMargin
        )
        stmt.execute(
          """CREATE INDEX IF NOT EXISTS idx_history_event_outbox_pending
            |ON history_event_outbox(delivered_at, id)""".stripMargin
        )
      finally stmt.close()

  private def metadata(payloadJson: String): Either[String, (String, String, String)] =
    try
      val obj = ujson.read(payloadJson).obj
      Right((obj("type").str, obj("sessionId").str, obj("gameId").str))
    catch case NonFatal(e) =>
      Left(s"Invalid History outbox event JSON: ${e.getMessage}")

  private def update(
    sql: String,
    bind: java.sql.PreparedStatement => Unit,
    id: Long
  ): Either[String, Unit] =
    try
      val stmt = conn.prepareStatement(sql)
      try
        bind(stmt)
        stmt.setLong(2, id)
        stmt.executeUpdate()
        Right(())
      finally stmt.close()
    catch case NonFatal(e) => Left(e.getMessage)

  private def allEntries(): Either[String, List[HistoryOutboxEntry]] =
    synchronized:
      try
        val stmt = conn.prepareStatement(
          """SELECT id, event_type, session_id, game_id, payload_json, created_at,
            |       attempts, last_error, delivered_at
            |FROM history_event_outbox
            |ORDER BY id ASC""".stripMargin
        )
        try
          val rs = stmt.executeQuery()
          try
            val b = List.newBuilder[HistoryOutboxEntry]
            while rs.next() do b += entry(rs)
            Right(b.result())
          finally rs.close()
        finally stmt.close()
      catch case NonFatal(e) => Left(e.getMessage)

  private def entry(rs: ResultSet): HistoryOutboxEntry =
    HistoryOutboxEntry(
      id          = rs.getLong("id"),
      eventType   = rs.getString("event_type"),
      sessionId   = rs.getString("session_id"),
      gameId      = rs.getString("game_id"),
      payloadJson = rs.getString("payload_json"),
      createdAt   = Instant.parse(rs.getString("created_at")),
      attempts    = rs.getInt("attempts"),
      lastError   = Option(rs.getString("last_error")),
      deliveredAt = Option(rs.getString("delivered_at")).map(Instant.parse)
    )
