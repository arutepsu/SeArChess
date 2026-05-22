package chess.historyservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
=======
>>>>>>> 966317ea (added bot container)
=======
>>>>>>> 8b003a1f (Use schema-isolated Slick Postgres persistence for history service)

final case class HistoryServiceRuntime(
    shutdownHttp: IO[Unit],
    closeStorage: () => Unit,
    stopConsumer: () => Unit = () => ()
<<<<<<< HEAD
<<<<<<< HEAD
=======
>>>>>>> 8b003a1f (Use schema-isolated Slick Postgres persistence for history service)
):
  def shutdown(): Unit =
    stopConsumer()
    shutdownHttp.unsafeRunSync()
    closeStorage()
<<<<<<< HEAD
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
=======
>>>>>>> 8b003a1f (Use schema-isolated Slick Postgres persistence for history service)
