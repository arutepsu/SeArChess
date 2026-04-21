package chess.aiservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder

/** AI Service composition root and HTTP runtime startup. */
object AiServiceWiring:

  def start(config: AiServiceConfig): AiServiceRuntime =
    val httpApp = AiServiceRoutes(config).routes.orNotFound
    val host = Host
      .fromString(config.host)
      .getOrElse(throw RuntimeException(s"Invalid AI_HTTP_HOST: ${config.host}"))
    val port = Port
      .fromInt(config.port)
      .getOrElse(throw RuntimeException(s"Invalid AI_HTTP_PORT: ${config.port}"))

    val (_, shutdown) =
      EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(httpApp)
        .build
        .allocated
        .unsafeRunSync()

    AiServiceRuntime(shutdown)
