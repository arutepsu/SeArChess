package chess.tournamentservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder

object TournamentServiceWiring:

  def start(config: TournamentServiceConfig): TournamentServiceRuntime =
    val registry = DefaultBotRegistry(config)
    val service  = TournamentJobService.create(registry, config, SparkTournamentAnalyticsProcessRunner()).unsafeRunSync()
    val worker   = service.startWorker().unsafeRunSync()
    val analyticsWorkers = service.startAnalyticsWorkers().unsafeRunSync()
    val httpApp  = TournamentRoutes(service).routes.orNotFound

    val host = Host
      .fromString(config.host)
      .getOrElse(throw RuntimeException(s"Invalid TOURNAMENT_HTTP_HOST: ${config.host}"))
    val port = Port
      .fromInt(config.port)
      .getOrElse(throw RuntimeException(s"Invalid TOURNAMENT_HTTP_PORT: ${config.port}"))

    val (_, shutdownHttp) =
      EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(httpApp)
        .build
        .allocated
        .unsafeRunSync()

    TournamentServiceRuntime(shutdownHttp, worker, analyticsWorkers)
