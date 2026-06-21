package chess.gatewayservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global

final class GatewayServiceRuntime(shutdownHttp: IO[Unit], shutdownClient: IO[Unit]):
  def shutdown(): Unit =
    shutdownHttp.unsafeRunSync()
    shutdownClient.unsafeRunSync()
