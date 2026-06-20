package chess.streaming

import chess.streaming.DslCommand.*
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.*

final class DslParserFlowSpec extends AnyFlatSpec with Matchers:

  "ParseDslFlow" should "parse valid DSL lines and ignore comments and empty lines" in {
    implicit val system: ActorSystem = ActorSystem("DslParserFlowSpec")

    try {
      val parsed = Await.result(
        Source(List("# comment", "", "session demo-1", "players Alice Bob", "move Alice e2e4", "status", "resign Bob"))
          .via(SearchessReactiveStreams.parseDslFlow)
          .runWith(Sink.seq),
        5.seconds
      )

      parsed shouldBe Seq(
        Right(SessionStartedCommand(3, "demo-1")),
        Right(PlayersCommand(4, "Alice", "Bob")),
        Right(MoveCommand(5, "Alice", "e2e4")),
        Right(StatusCommand(6)),
        Right(ResignCommand(7, "Bob"))
      )
    } finally {
      Await.result(system.terminate(), 5.seconds)
    }
  }

  it should "report malformed lines as data" in {
    implicit val system: ActorSystem = ActorSystem("DslParserFlowMalformedSpec")

    try {
      val parsed = Await.result(
        Source(List("unknown abc"))
          .via(SearchessReactiveStreams.parseDslFlow)
          .runWith(Sink.seq),
        5.seconds
      )

      parsed shouldBe Seq(Left(DslParseError(1, "unknown abc", "unknown command: unknown")))
    } finally {
      Await.result(system.terminate(), 5.seconds)
    }
  }
