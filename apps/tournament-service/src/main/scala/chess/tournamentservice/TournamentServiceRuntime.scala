package chess.tournamentservice

import cats.effect.{FiberIO, IO}
import cats.effect.unsafe.implicits.global

final case class TournamentServiceRuntime(shutdownHttp: IO[Unit], worker: FiberIO[Nothing]):
  def shutdown(): Unit =
    worker.cancel.unsafeRunSync()
    shutdownHttp.unsafeRunSync()
