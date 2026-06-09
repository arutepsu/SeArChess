package chess.userservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.semigroupk.*
import chess.observability.StructuredLog
import chess.userservice.application.{LichessOAuthService, LichessTokenCipher, UserProfileService}
import chess.userservice.postgres.{
  SlickExternalAccountLinkRepository,
  SlickOAuthLinkStateRepository,
  SlickUserProfileRepository,
  UserFlywayInitializer
}
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import slick.jdbc.PostgresProfile.api.Database

object UserServiceWiring:

  def start(config: UserServiceConfig): UserServiceRuntime =
    val schema = config.postgresSchema

    // Outbound HTTP client (Lichess OAuth calls)
    val (httpClient, shutdownClient) = EmberClientBuilder
      .default[IO]
      .build
      .allocated
      .unsafeRunSync()

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

    val profileRepo  = SlickUserProfileRepository(db, schema)
    val linkRepo     = SlickExternalAccountLinkRepository(db, schema)
    val stateRepo    = SlickOAuthLinkStateRepository(db, schema)
    val service      = UserProfileService(profileRepo, linkRepo)
    val tokenCipher: Option[LichessTokenCipher] = config.lichessTokenEncryptionKey.flatMap { key =>
      LichessTokenCipher.fromBase64Key(key) match
        case Right(c)  => Some(c)
        case Left(err) =>
          StructuredLog.warn("user-service", "token_cipher_init_failed", "reason" -> err)
          None
    }
    val oauthService = LichessOAuthService(stateRepo, linkRepo, httpClient, config.lichessOAuth, tokenCipher)
    val routes         = UserRoutes(service, oauthService, config.lichessOAuth)
    val internalRoutes = InternalLichessRoutes(linkRepo, config.internalApiKey)

    val httpApp = (routes.routes <+> internalRoutes.routes).orNotFound

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

    UserServiceRuntime(shutdownHttp, shutdownClient, () => db.close())
