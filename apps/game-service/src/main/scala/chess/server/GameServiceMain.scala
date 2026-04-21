package chess.server

import cats.effect.unsafe.implicits.global
<<<<<<< HEAD
import chess.observability.StructuredLog
=======
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
import chess.server.config.{AppConfig, ConfigLoader}

/** Independent Game Service entry point. */
object GameServiceMain:

  def main(args: Array[String]): Unit =
    val config = ConfigLoader.loadOrExit()
    run(args, config)

  private[chess] def run(args: Array[String], config: AppConfig): Unit =
    val aiDesc = config.ai.mode match
<<<<<<< HEAD
      case chess.server.config.AiProviderMode.Remote =>
        s"remote @ ${config.ai.remote.map(_.baseUrl).getOrElse("(no URL)")}"
      case chess.server.config.AiProviderMode.LocalDeterministic => "local-deterministic"
      case chess.server.config.AiProviderMode.Disabled           => "disabled"
    StructuredLog.info(
      "game-service",
      "startup_config",
      "httpHost" -> config.http.host,
      "httpPort" -> config.http.port,
      "webSocketEnabled" -> config.webSocket.enabled,
      "persistence" -> config.persistence.toString,
      "postgresSchema" -> config.postgres.flatMap(_.schema),
      "ai" -> aiDesc,
      "aiInteraction" -> config.ai.interaction.toString,
      "aiStartupPolicy" -> config.ai.startupPolicy.toString,
      "aiFailureBehaviour" -> config.ai.failureBehaviour.toString,
      "historyForwardingEnabled" -> config.history.enabled,
      "historyBaseUrl" -> config.history.baseUrl,
      "historyInteraction" -> config.history.interaction.toString,
      "historyStartupPolicy" -> config.history.startupPolicy.toString,
      "historyFailureBehaviour" -> config.history.failureBehaviour.toString
    )
    val (_, server) = ServerWiring.start(config)
    StructuredLog.info(
      "game-service",
      "started",
      "httpHost" -> config.http.host,
      "httpPort" -> config.http.port,
      "healthPath" -> chess.server.http.GameServiceHttpSurface.PublicHealthPath
    )

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      StructuredLog.info("game-service", "shutdown_started")
      server.shutdownHttp.unsafeRunSync()
      server.shutdownEvents.unsafeRunSync()
      server.wsServer.foreach(_.stop(0))
      StructuredLog.info("game-service", "shutdown_completed")
=======
      case chess.server.config.AiProviderMode.Remote             => s"remote @ ${config.ai.remote.map(_.baseUrl).getOrElse("(no URL)")}"
      case chess.server.config.AiProviderMode.LocalDeterministic => "local-deterministic"
      case chess.server.config.AiProviderMode.Disabled           => "disabled"
    println(s"[game] AI dependency: $aiDesc (${config.ai.interaction}, startup=${config.ai.startupPolicy}, failure=${config.ai.failureBehaviour})")
    println(s"[game] History forwarding: enabled=${config.history.enabled} (${config.history.interaction}, startup=${config.history.startupPolicy}, failure=${config.history.failureBehaviour})")
    val (_, server) = ServerWiring.start(config)

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      server.shutdownHttp.unsafeRunSync()
      server.shutdownEvents.unsafeRunSync()
      server.wsServer.foreach(_.stop(0))
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
    }))

    Thread.currentThread().join()
