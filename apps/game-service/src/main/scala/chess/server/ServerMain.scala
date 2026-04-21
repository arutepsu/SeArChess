package chess.server

import chess.server.config.AppConfig

/** Backwards-compatible alias for the Game Service entry point. */
object ServerMain:

  def main(args: Array[String]): Unit =
    GameServiceMain.main(args)

  private[chess] def run(args: Array[String], config: AppConfig): Unit =
    GameServiceMain.run(args, config)
