package chess.historyservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global

final case class HistoryServiceRuntime(
    shutdownHttp: IO[Unit],
    closeStorage: () => Unit,
    stopConsumer: () => Unit = () => ()
):
  def shutdown(): Unit =
    stopConsumer()
    shutdownHttp.unsafeRunSync()
    closeStorage()
