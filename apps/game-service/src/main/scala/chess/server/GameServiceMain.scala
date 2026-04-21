package chess.server

import cats.effect.unsafe.implicits.global
import chess.server.config.{AppConfig, ConfigLoader}

/** Independent Game Service entry point. */
object GameServiceMain:

  def main(args: Array[String]): Unit =
    val config = ConfigLoader.loadOrExit()
    run(args, config)

  private[chess] def run(args: Array[String], config: AppConfig): Unit =
    val aiDesc = config.ai.mode match
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
    }))

    Thread.currentThread().join()
