package chess.userservice

import cats.effect.IO

final case class UserServiceRuntime(
    shutdownHttp: IO[Unit],
    shutdownStorage: () => Unit
)
