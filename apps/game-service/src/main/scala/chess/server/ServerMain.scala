package chess.server

import chess.server.config.AppConfig

/** Backwards-compatible alias for the Game Service entry point. */
object ServerMain:

  def main(args: Array[String]): Unit =
    GameServiceMain.main(args)

  private[chess] def run(args: Array[String], config: AppConfig): Unit =
<<<<<<< HEAD
    GameServiceMain.run(args, config)
=======
    val aiDesc = config.ai.mode match
      case chess.config.AiProviderMode.Remote             => s"remote @ ${config.ai.remote.map(_.baseUrl).getOrElse("(no URL)")}"
      case chess.config.AiProviderMode.LocalDeterministic => "local-deterministic"
      case chess.config.AiProviderMode.Disabled           => "disabled"
    println(s"[chess] AI client: $aiDesc")
    val (_, server) = ServerWiring.start(config)

    // Drain HTTP and WebSocket on JVM shutdown (SIGINT / SIGTERM).
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      server.shutdownHttp.unsafeRunSync()
      server.shutdownEvents.unsafeRunSync()
      server.wsServer.foreach(_.stop(0))
    }))

    // Server threads keep the JVM alive; block main until interrupted.
    Thread.currentThread().join()
>>>>>>> 14542117 (fix ai flow)
