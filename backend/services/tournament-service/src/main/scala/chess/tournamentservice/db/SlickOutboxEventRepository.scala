package chess.tournamentservice.db

import cats.effect.IO
import slick.jdbc.GetResult
import slick.jdbc.PostgresProfile.api.*

import java.time.Instant
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

final class SlickOutboxEventRepository(db: Database, schema: String) extends OutboxEventRepository:

  private val t = schema
  private given ec: ExecutionContext = ExecutionContext.global

  private given GetResult[OutboxEvent] = GetResult { r =>
    OutboxEvent(
      eventId       = r.nextString(),
      aggregateType = r.nextString(),
      aggregateId   = r.nextString(),
      eventType     = r.nextString(),
      payloadJson   = r.nextString(),
      status        = r.nextString(),
      createdAt     = Instant.parse(r.nextString()),
      publishedAt   = r.nextStringOption().map(Instant.parse),
      lastError     = r.nextStringOption(),
      attemptCount  = r.nextInt()
    )
  }

  def fetchPending(limit: Int): IO[List[OutboxEvent]] =
    run(sql"""
      SELECT event_id, aggregate_type, aggregate_id, event_type, payload_json,
             status, created_at, published_at, last_error, attempt_count
      FROM #$t.tournament_outbox_events
      WHERE status = 'pending'
      ORDER BY created_at ASC
      LIMIT $limit
    """.as[OutboxEvent]).map(_.toList)

  def markPublished(eventId: String): IO[Unit] =
    val now = Instant.now().toString
    run(sqlu"""
      UPDATE #$t.tournament_outbox_events
      SET status = 'published', published_at = $now
      WHERE event_id = $eventId
    """).void

  def markFailed(eventId: String, error: String): IO[Unit] =
    run(sqlu"""
      UPDATE #$t.tournament_outbox_events
      SET status = 'failed',
          last_error = $error,
          attempt_count = attempt_count + 1
      WHERE event_id = $eventId
    """).void

  def countPending(): IO[Int] =
    run(sql"SELECT COUNT(*) FROM #$t.tournament_outbox_events WHERE status = 'pending'".as[Long])
      .map(_.headOption.fold(0)(_.toInt))

  def countFailed(): IO[Int] =
    run(sql"SELECT COUNT(*) FROM #$t.tournament_outbox_events WHERE status = 'failed'".as[Long])
      .map(_.headOption.fold(0)(_.toInt))

  private def run[T](action: DBIO[T]): IO[T] =
    IO.blocking(Await.result(db.run(action), 30.seconds))
