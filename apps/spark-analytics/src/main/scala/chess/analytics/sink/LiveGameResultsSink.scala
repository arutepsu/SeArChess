package chess.analytics.sink

import org.apache.spark.sql.DataFrame
import chess.analytics.config.PostgresConfig
import java.sql.{DriverManager, PreparedStatement, Timestamp}
import java.time.OffsetDateTime

object LiveGameResultsSink {

  private val table = "public.live_game_results"

  private val ddl =
    s"""CREATE TABLE IF NOT EXISTS $table (
       |  event_id       TEXT        NOT NULL,
       |  aggregate_id   TEXT        NOT NULL,
       |  session_id     TEXT,
       |  occurred_at    TIMESTAMPTZ NOT NULL,
       |  result         TEXT        NOT NULL,
       |  winner         TEXT,
       |  draw_reason    TEXT,
       |  correlation_id TEXT,
       |  ingested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
       |  CONSTRAINT live_game_results_pk PRIMARY KEY (event_id)
       |)""".stripMargin

  private val upsert =
    s"""INSERT INTO $table
       |  (event_id, aggregate_id, session_id, occurred_at, result, winner, draw_reason, correlation_id)
       |VALUES (?, ?, ?, ?, ?, ?, ?, ?)
       |ON CONFLICT (event_id) DO NOTHING""".stripMargin

  def ensureTableExists(config: PostgresConfig): Unit = {
    Class.forName("org.postgresql.Driver")
    val conn = DriverManager.getConnection(config.url, config.user, config.password)
    try {
      val stmt = conn.createStatement()
      stmt.execute(ddl)
      stmt.close()
      println(s"[LiveGameResultsSink] Table $table ready.")
    } finally {
      conn.close()
    }
  }

  def writeBatch(batch: DataFrame, config: PostgresConfig): Unit = {
    val rows = batch.collect()
    if (rows.isEmpty) return

    Class.forName("org.postgresql.Driver")
    val conn = DriverManager.getConnection(config.url, config.user, config.password)
    try {
      conn.setAutoCommit(false)
      val ps: PreparedStatement = conn.prepareStatement(upsert)
      try {
        rows.foreach { row =>
          ps.setString(1, row.getAs[String]("event_id"))
          ps.setString(2, row.getAs[String]("aggregate_id"))
          ps.setString(3, row.getAs[String]("session_id"))
          val tsStr = row.getAs[String]("occurred_at")
          val ts    = if (tsStr != null) Timestamp.from(OffsetDateTime.parse(tsStr).toInstant) else null
          ps.setTimestamp(4, ts)
          ps.setString(5, row.getAs[String]("result"))
          ps.setString(6, row.getAs[String]("winner"))
          ps.setString(7, row.getAs[String]("draw_reason"))
          ps.setString(8, row.getAs[String]("correlation_id"))
          ps.addBatch()
        }
        ps.executeBatch()
        conn.commit()
        println(s"[LiveGameResultsSink] Wrote ${rows.length} rows (duplicates skipped via ON CONFLICT).")
      } catch {
        case e: Exception =>
          conn.rollback()
          println(s"[LiveGameResultsSink ERROR] Batch write failed: ${e.getMessage}")
          throw e
      } finally {
        ps.close()
      }
    } finally {
      conn.close()
    }
  }
}
