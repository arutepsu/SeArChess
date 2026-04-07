package chess

import chess.application.ObservableGame
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MainSpec extends AnyFlatSpec with Matchers:

  "DefaultAppBootstrap" should "delegate prepare and launch to injected functions" in {
    var preparedGame: Option[ObservableGame] = None
    var launchedArgs: Option[Array[String]] = None

    val bootstrap = new Main.DefaultAppBootstrap(
      prepareFn = game => preparedGame = Some(game),
      launchFn = args => launchedArgs = Some(args)
    )

    val game = new ObservableGame()
    val args = Array("a", "b")

    bootstrap.prepare(game)
    bootstrap.launch(args)

    preparedGame shouldBe Some(game)
    launchedArgs.get.toSeq shouldBe args.toSeq
  }

  "Main.run" should "prepare before launch" in {
    val calls = scala.collection.mutable.ListBuffer.empty[String]

    val bootstrap = new Main.AppBootstrap:
      override def prepare(game: ObservableGame): Unit =
        calls += "prepare"

      override def launch(args: Array[String]): Unit =
        calls += "launch"

    Main.run(Array.empty, bootstrap)

    calls.toList shouldBe List("prepare", "launch")
  }

  it should "create a game and pass args through" in {
    var prepared = false
    var launchedArgs: Option[Array[String]] = None

    val bootstrap = new Main.AppBootstrap:
      override def prepare(game: ObservableGame): Unit =
        prepared = game != null

      override def launch(args: Array[String]): Unit =
        launchedArgs = Some(args)

    val args = Array("x", "y")
    Main.run(args, bootstrap)

    prepared shouldBe true
    launchedArgs.get.toSeq shouldBe args.toSeq
  }

  "Main.main" should "delegate to run using the default bootstrap" in {
    var preparedGame: Option[ObservableGame] = None
    var launchedArgs: Option[Array[String]] = None

    object TestMain:
      def main(args: Array[String]): Unit =
        Main.run(
          args,
          new Main.DefaultAppBootstrap(
            prepareFn = game => preparedGame = Some(game),
            launchFn = arr => launchedArgs = Some(arr)
          )
        )

    val args = Array("p", "q")
    TestMain.main(args)

    preparedGame should not be empty
    launchedArgs.get.toSeq shouldBe args.toSeq
  }