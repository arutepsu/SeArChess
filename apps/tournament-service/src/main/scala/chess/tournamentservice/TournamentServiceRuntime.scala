package chess.tournamentservice

import cats.effect.{FiberIO, IO}
import cats.effect.unsafe.implicits.global

final case class TournamentServiceRuntime(
    shutdownHttp: IO[Unit],
    worker: FiberIO[Nothing],
    analyticsWorkers: List[FiberIO[Nothing]],
    outboxPoller: Option[FiberIO[Nothing]],
    closeResources: IO[Unit] = IO.unit
):
  def shutdown(): Unit =
    outboxPoller.foreach(_.cancel.unsafeRunSync())
    worker.cancel.unsafeRunSync()
    analyticsWorkers.foreach(_.cancel.unsafeRunSync())
    shutdownHttp.unsafeRunSync()
    closeResources.unsafeRunSync()
