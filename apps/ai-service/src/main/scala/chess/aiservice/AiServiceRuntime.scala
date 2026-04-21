package chess.aiservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global

final case class AiServiceRuntime(shutdownHttp: IO[Unit]):
  def shutdown(): Unit =
    shutdownHttp.unsafeRunSync()
