package chess.gatewayservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import scala.concurrent.duration.*

object GatewayServiceWiring:

  def start(config: GatewayServiceConfig): GatewayServiceRuntime =
    val (httpClient, shutdownClient) =
      EmberClientBuilder.default[IO]
        .withTimeout(24.hours)  // allow long-lived NDJSON streaming connections
        .build.allocated.unsafeRunSync()

    val jwtCache  = new TournamentJwtCache()
    val authBridge = new TournamentAuthBridge(httpClient, config.tournamentServerUrl, jwtCache)
    val routes    = new TournamentGatewayRoutes(httpClient, config, authBridge)
    val httpApp = routes.routes.orNotFound

    val host = Host
      .fromString(config.host)
      .getOrElse(throw RuntimeException(s"Invalid GATEWAY_HTTP_HOST: ${config.host}"))
    val port = Port
      .fromInt(config.port)
      .getOrElse(throw RuntimeException(s"Invalid GATEWAY_HTTP_PORT: ${config.port}"))

    val (_, shutdownHttp) =
      EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(httpApp)
        .build
        .allocated
        .unsafeRunSync()

    GatewayServiceRuntime(shutdownHttp, shutdownClient)
