package chess.tournamentservice.db

import cats.effect.IO

trait OutboxEventRepository:
  def fetchPending(limit: Int): IO[List[OutboxEvent]]
  def markPublished(eventId: String): IO[Unit]
  def markFailed(eventId: String, error: String): IO[Unit]
  def countPending(): IO[Int]
  def countFailed(): IO[Int]
