package chess.historyservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
<<<<<<< HEAD

final case class HistoryServiceRuntime(
    shutdownHttp: IO[Unit],
    closeStorage: () => Unit,
    stopConsumer: () => Unit = () => ()
):
  def shutdown(): Unit =
    stopConsumer()
    shutdownHttp.unsafeRunSync()
    closeStorage()
=======
import chess.history.sqlite.SqliteArchiveRepository

final case class HistoryServiceRuntime(
  shutdownHttp: IO[Unit],
  repository:   SqliteArchiveRepository
):
  def shutdown(): Unit =
    shutdownHttp.unsafeRunSync()
    repository.close()
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
