package chess.lichessbridge

import cats.effect.IO
import cats.effect.unsafe.implicits.global

/** Holds the shutdown actions for the HTTP server and background worker. */
final case class LichessBridgeRuntime(
    shutdownHttp: IO[Unit],
    releaseWorker: IO[Unit]
):
  def shutdown(): Unit =
    releaseWorker.unsafeRunSync()
    shutdownHttp.unsafeRunSync()
