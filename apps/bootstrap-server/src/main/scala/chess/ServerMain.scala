package chess

import cats.effect.unsafe.implicits.global
import chess.config.{AppConfig, ConfigLoader}

/** Entry point for server-only deployment.
 *
 *  Loads config from environment variables via [[ConfigLoader]], then starts
 *  the full backend infrastructure via [[SharedWiring.start]]:
 *  in-memory repositories, WebSocket server (if enabled), application services,
 *  and HTTP REST server.
 *
 *  Does NOT start the ScalaFX GUI or TUI.  A JVM shutdown hook ensures the
 *  HTTP and WebSocket servers are drained cleanly on SIGINT or SIGTERM before
 *  the process exits.  The main thread blocks until interrupted.
 *
 *  To run:
 *  {{{
 *    sbt "bootstrapServer/runMain chess.ServerMain"
 *  }}}
 */
object ServerMain:

  def main(args: Array[String]): Unit =
    val config = ConfigLoader.loadOrExit()
    run(args, config)

  /** Start the server composition with a pre-loaded config.
   *
   *  Separated from [[main]] so that [[Main]] can load config once and
   *  dispatch here without reloading.
   */
  private[chess] def run(args: Array[String], config: AppConfig): Unit =
    val wiring = SharedWiring.start(config)

    // Drain HTTP and WebSocket on JVM shutdown (SIGINT / SIGTERM).
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      wiring.shutdownHttp.unsafeRunSync()
      wiring.wsServer.foreach(_.stop(0))
    }))

    // Server threads keep the JVM alive; block main until interrupted.
    Thread.currentThread().join()
