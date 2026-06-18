package chess.streaming

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Keep
import scala.concurrent.ExecutionContext

object ChessStreamingMain {

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("ChessStreamingSystem")
    implicit val ec: ExecutionContext = system.dispatcher

    println("=========================================================")
    println("      SEARCHESS REACTIVE STREAM PIPELINE (PEKKO)        ")
    println("=========================================================")

    val runningStream = SearchessReactiveStreams
      .run(args)
      .toMat(SearchessReactiveStreams.consoleSink)(Keep.right)
      .run()

    runningStream.onComplete { result =>
      result.foreach { summary =>
        println("=========================================================")
        println(s"events=${summary.totalEvents}")
        println(s"acceptedMoves=${summary.acceptedMoves}")
        println(s"rejectedMoves=${summary.rejectedMoves}")
        println(s"parseFailures=${summary.parseFailures}")
        println(s"validationFailures=${summary.validationFailures}")
        println(s"finishedGames=${summary.finishedGames}")
      }
      println("\nSearchess reactive stream completed. Terminating ActorSystem...")
      system.terminate()
    }
  }
}
