package chess.userservice

import cats.effect.unsafe.implicits.global
import chess.observability.StructuredLog

object UserServiceMain:

  def main(args: Array[String]): Unit =
    val config = UserServiceConfig.loadOrExit()
    StructuredLog.info(
      "user-service",
      "startup_config",
      "httpHost"                         -> config.host,
      "httpPort"                         -> config.port,
      "postgresSchema"                   -> config.postgresSchema.getOrElse("(default)"),
      "lichessOAuthEnabled"                 -> config.lichessOAuth.isConfigured,
      "lichessTokenEncryptionKeyConfigured" -> config.lichessTokenEncryptionKey.isDefined,
      "lichessBotUsername"                  -> config.lichessChallenge.botUsername
    )

    val runtime = UserServiceWiring.start(config)
    StructuredLog.info(
      "user-service",
      "started",
      "httpHost"   -> config.host,
      "httpPort"   -> config.port,
      "healthPath" -> "/health"
    )

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      StructuredLog.info("user-service", "shutdown_started")
      runtime.shutdownHttp.unsafeRunSync()
      runtime.shutdownClient.unsafeRunSync()
      runtime.shutdownStorage()
      StructuredLog.info("user-service", "shutdown_completed")
    }))

    Thread.currentThread().join()
