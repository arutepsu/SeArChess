package chess

import chess.config.{AppMode, ConfigLoader}

/** Default `sbt run` entry point — loads config and dispatches by [[AppMode]].
 *
 *  With no environment variables set (or `APP_MODE=desktop`), delegates to
 *  [[DesktopMain]] exactly as before.  Set `APP_MODE=server` to start in
 *  server-only mode without the GUI or TUI.
 *
 *  Config is loaded once here and forwarded to the selected entry point's
 *  `run` method so it is not parsed twice.
 *
 *  Prefer [[DesktopMain]] or [[ServerMain]] directly when launching from
 *  outside sbt or in deployment scripts.
 */
object Main:
  def main(args: Array[String]): Unit =
    val config = ConfigLoader.loadOrExit()
    config.mode match
      case AppMode.Desktop => DesktopMain.run(args, config)
      case AppMode.Server  => ServerMain.run(args, config)
