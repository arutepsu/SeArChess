package chess

import chess.adapter.gui.ChessApp
import chess.application.ObservableGame

object Main:

  private[chess] trait AppBootstrap:
    def prepare(game: ObservableGame): Unit
    def launch(args: Array[String]): Unit

  private[chess] final class DefaultAppBootstrap(
      prepareFn: ObservableGame => Unit = ChessApp.prepareWith,
      launchFn: Array[String] => Unit = ChessApp.main
  ) extends AppBootstrap:
    override def prepare(game: ObservableGame): Unit =
      prepareFn(game)

    override def launch(args: Array[String]): Unit =
      launchFn(args)

  def main(args: Array[String]): Unit =
    run(args, new DefaultAppBootstrap())

  private[chess] def run(args: Array[String], bootstrap: AppBootstrap): Unit =
    val game = new ObservableGame()
    bootstrap.prepare(game)
    bootstrap.launch(args)