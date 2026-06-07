package chess.userservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.observability.StructuredLog
import chess.userservice.application.UserProfileService
import chess.userservice.postgres.{SlickExternalAccountLinkRepository, SlickUserProfileRepository, UserFlywayInitializer}
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder
import slick.jdbc.PostgresProfile.api.Database

object UserServiceWiring:

  def start(config: UserServiceConfig): UserServiceRuntime =
    val schema = config.postgresSchema

    val migrateResult = UserFlywayInitializer.migrate(
      config.postgresUrl, config.postgresUser, config.postgresPassword, schema
    )
    StructuredLog.info(
      "user-service",
      "postgres_schema_migrated",
      "migrationsExecuted" -> migrateResult.migrationsExecuted,
      "schemaVersion"      -> migrateResult.schemaVersion.getOrElse("none")
    )

    val db = Database.forURL(
      url      = config.postgresUrl,
      user     = config.postgresUser,
      password = config.postgresPassword,
      driver   = "org.postgresql.Driver"
    )

    val profileRepo = SlickUserProfileRepository(db, schema)
    val linkRepo    = SlickExternalAccountLinkRepository(db, schema)
    val service     = UserProfileService(profileRepo, linkRepo)
    val routes      = UserRoutes(service)

    val httpApp = routes.routes.orNotFound

    val host = Host.fromString(config.host).getOrElse(
      throw RuntimeException(s"[user-service] Invalid USER_HTTP_HOST: '${config.host}'")
    )
    val port = Port.fromInt(config.port).getOrElse(
      throw RuntimeException(s"[user-service] USER_HTTP_PORT out of range: ${config.port}")
    )

    val (_, shutdownHttp) = EmberServerBuilder
      .default[IO]
      .withHost(host)
      .withPort(port)
      .withHttpApp(httpApp)
      .build
      .allocated
      .unsafeRunSync()

    UserServiceRuntime(shutdownHttp, () => db.close())
