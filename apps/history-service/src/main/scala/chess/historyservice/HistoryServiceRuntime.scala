package chess.historyservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.history.sqlite.SqliteArchiveRepository

final case class HistoryServiceRuntime(
  shutdownHttp: IO[Unit],
  repository:   SqliteArchiveRepository
):
  def shutdown(): Unit =
    shutdownHttp.unsafeRunSync()
    repository.close()
