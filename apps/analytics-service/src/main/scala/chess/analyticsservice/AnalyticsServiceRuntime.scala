package chess.analyticsservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import _root_.slick.jdbc.PostgresProfile.api.Database

final case class AnalyticsServiceRuntime(shutdownHttp: IO[Unit], db: Database):
  def shutdown(): Unit =
    shutdownHttp.unsafeRunSync()
    db.close()
