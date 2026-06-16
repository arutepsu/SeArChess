package chess.tournamentservice.db

import cats.effect.{IO, Ref}

import java.time.Instant

final class InMemoryOutboxEventRepository private (
    events: Ref[IO, Map[String, OutboxEvent]]
) extends OutboxEventRepository:

  def fetchPending(limit: Int): IO[List[OutboxEvent]] =
    events.get.map(
      _.values
        .filter(_.status == "pending")
        .toList
        .sortBy(_.createdAt.toEpochMilli)
        .take(limit)
    )

  def markPublished(eventId: String): IO[Unit] =
    val now = Instant.now()
    events.update { current =>
      current.get(eventId) match
        case Some(ev) => current.updated(eventId, ev.copy(status = "published", publishedAt = Some(now)))
        case None     => current
    }

  def markFailed(eventId: String, error: String): IO[Unit] =
    events.update { current =>
      current.get(eventId) match
        case Some(ev) => current.updated(eventId, ev.copy(
          status       = "failed",
          lastError    = Some(error),
          attemptCount = ev.attemptCount + 1
        ))
        case None => current
    }

  def countPending(): IO[Int] =
    events.get.map(_.values.count(_.status == "pending"))

  def countFailed(): IO[Int] =
    events.get.map(_.values.count(_.status == "failed"))

  def allEvents(): IO[List[OutboxEvent]] =
    events.get.map(_.values.toList)

object InMemoryOutboxEventRepository:
  def create(): IO[InMemoryOutboxEventRepository] =
    Ref.of[IO, Map[String, OutboxEvent]](Map.empty).map(new InMemoryOutboxEventRepository(_))

  def createWith(events: List[OutboxEvent]): IO[InMemoryOutboxEventRepository] =
    Ref.of[IO, Map[String, OutboxEvent]](
      events.map(e => e.eventId -> e).toMap
    ).map(new InMemoryOutboxEventRepository(_))
