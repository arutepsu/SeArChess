package chess.historyservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
<<<<<<< HEAD
<<<<<<< HEAD
=======
>>>>>>> 966317ea (added bot container)

final case class HistoryServiceRuntime(
    shutdownHttp: IO[Unit],
    closeStorage: () => Unit,
    stopConsumer: () => Unit = () => ()
<<<<<<< HEAD
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
=======
>>>>>>> 966317ea (added bot container)
):
  def shutdown(): Unit =
    stopConsumer()
    shutdownHttp.unsafeRunSync()
<<<<<<< HEAD
    repository.close()
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
=======
    closeStorage()
>>>>>>> 966317ea (added bot container)
