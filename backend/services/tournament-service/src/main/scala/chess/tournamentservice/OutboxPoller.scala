package chess.tournamentservice

import cats.effect.IO
import chess.observability.StructuredLog
import chess.tournamentservice.db.{OutboxEvent, OutboxEventRepository}

import scala.concurrent.duration.FiniteDuration

final class OutboxPoller(
    repo: OutboxEventRepository,
    publisher: OutboxPublisher,
    pollInterval: FiniteDuration,
    batchSize: Int
):
  def start(): IO[Nothing] =
    (pollOnce() >> IO.sleep(pollInterval)).foreverM

  def pollOnce(): IO[Unit] =
    repo.fetchPending(batchSize).flatMap { events =>
      events.foldLeft(IO.unit)((acc, event) => acc >> processOne(event))
    }

  private def processOne(event: OutboxEvent): IO[Unit] =
    publisher.publish(event).attempt.flatMap {
      case Right(_) =>
        repo.markPublished(event.eventId)
      case Left(err) =>
        val msg = Option(err.getMessage).getOrElse("unknown error")
        IO(StructuredLog.error(
          "tournament-service",
          "outbox_publish_failed",
          "eventId"   -> event.eventId,
          "eventType" -> event.eventType,
          "error"     -> msg
        )) >> repo.markFailed(event.eventId, msg)
    }
