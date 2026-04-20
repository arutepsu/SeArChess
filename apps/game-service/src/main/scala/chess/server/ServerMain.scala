package chess.server

import cats.effect.unsafe.implicits.global
import chess.config.{AppConfig, ConfigLoader}

/** Entry point for server-only deployment.
 *
 *  Loads config from environment variables via [[ConfigLoader]], then starts
 *  the full server infrastructure via [[ServerWiring.start]]:
 *  in-memory repositories, WebSocket server (if enabled), application services,
 *  and HTTP REST server.
 *
 *  Does NOT start the ScalaFX GUI or TUI.  A JVM shutdown hook ensures the
 *  HTTP and WebSocket servers are drained cleanly on SIGINT or SIGTERM before
 *  the process exits.  The main thread blocks until interrupted.
 *
 *  To run:
 *  {{{
 *    sbt "gameService/runMain chess.server.ServerMain"
 *  }}}
 */
object ServerMain:

  def main(args: Array[String]): Unit =
    val config = ConfigLoader.loadOrExit()
    run(args, config)

  /** Start the server composition with a pre-loaded config.
   *
   *  Separated from [[main]] to allow callers to supply an already-loaded
   *  [[AppConfig]] without re-reading environment variables.
   */
  private[chess] def run(args: Array[String], config: AppConfig): Unit =
    val aiDesc = config.ai.mode match
      case chess.config.AiProviderMode.Remote             => s"remote @ ${config.ai.remote.map(_.baseUrl).getOrElse("(no URL)")}"
      case chess.config.AiProviderMode.LocalDeterministic => "local-deterministic"
      case chess.config.AiProviderMode.Disabled           => "disabled"
    println(s"[chess] AI provider: $aiDesc")
    val (_, server) = ServerWiring.start(config)

    // Drain HTTP and WebSocket on JVM shutdown (SIGINT / SIGTERM).
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      server.shutdownHttp.unsafeRunSync()
      server.wsServer.foreach(_.stop(0))
    }))

    // Server threads keep the JVM alive; block main until interrupted.
    Thread.currentThread().join()
