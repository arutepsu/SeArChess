package chess.userservice

import cats.effect.IO

final case class UserServiceRuntime(
    shutdownHttp: IO[Unit],
    shutdownClient: IO[Unit],
    shutdownStorage: () => Unit
)
