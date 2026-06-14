package chess.analyticsservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.analyticsservice.slick.SlickAnalyticsRepository
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder
import _root_.slick.jdbc.PostgresProfile.api.Database

object AnalyticsServiceWiring:

  def start(config: AnalyticsServiceConfig): AnalyticsServiceRuntime =
    val db = Database.forURL(
      url      = config.postgresUrl,
      user     = config.postgresUser,
      password = config.postgresPassword,
      driver   = "org.postgresql.Driver"
    )
    val repo    = new SlickAnalyticsRepository(db, config.postgresSchema)
    val httpApp = new AnalyticsRoutes(repo).routes.orNotFound

    val host = Host
      .fromString(config.host)
      .getOrElse(throw RuntimeException(s"Invalid ANALYTICS_HTTP_HOST: ${config.host}"))
    val port = Port
      .fromInt(config.port)
      .getOrElse(throw RuntimeException(s"Invalid ANALYTICS_HTTP_PORT: ${config.port}"))

    val (_, shutdownHttp) =
      EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(httpApp)
        .build
        .allocated
        .unsafeRunSync()

    AnalyticsServiceRuntime(shutdownHttp, db)
