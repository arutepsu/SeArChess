package chess.adapter.event

import chess.application.event.AppEvent
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, DrawReason, GameStatus, Move, Position}
import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.net.URI
import scala.collection.mutable

class HistoryHttpEventPublisherSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with OptionValues:

  private val sid = SessionId.random()
  private val gid = GameId.random()

  private final case class Posted(uri: URI, json: String, timeout: Int)

  private def publisher(posts: mutable.Buffer[Posted]): HistoryHttpEventPublisher =
    HistoryHttpEventPublisher(
      historyBaseUrl = "http://history.local:8081/",
      timeoutMillis = 1234,
      sendJson = (uri, json, timeout) => posts += Posted(uri, json, timeout)
    )

  "HistoryHttpEventPublisher" should "forward GameFinished events using the wire serializer JSON" in {
    val posts = mutable.Buffer.empty[Posted]
    val event = AppEvent.GameFinished(sid, gid, GameStatus.Checkmate(Color.White))

    publisher(posts).publish(event)

    posts should have size 1
    posts.head.uri.toString shouldBe s"http://history.local:8081${GameHistoryIngestionContract.GameEventsPath}"
    posts.head.timeout shouldBe 1234
    posts.head.json shouldBe AppEventSerializer.serialize(event).value
    ujson.read(posts.head.json)("type").str shouldBe "game.finished.v1"
  }

  it should "forward GameResigned events" in {
    val posts = mutable.Buffer.empty[Posted]
    publisher(posts).publish(AppEvent.GameResigned(sid, gid, Color.Black))

    posts should have size 1
    ujson.read(posts.head.json)("type").str shouldBe "game.resigned.v1"
  }

  it should "forward SessionCancelled events" in {
    val posts = mutable.Buffer.empty[Posted]
    publisher(posts).publish(AppEvent.SessionCancelled(sid, gid))

    posts should have size 1
    ujson.read(posts.head.json)("type").str shouldBe "game.session.cancelled.v1"
  }

  it should "ignore non-terminal boundary events" in {
    val posts = mutable.Buffer.empty[Posted]
    val e2 = Position.from(4, 1).value
    val e4 = Position.from(4, 3).value

    publisher(posts).publish(AppEvent.MoveApplied(sid, gid, Move(e2, e4), Color.White))

    posts shouldBe empty
  }

  it should "absorb HTTP sender failures" in {
    val bridge = HistoryHttpEventPublisher(
      historyBaseUrl = "http://history.local:8081",
      timeoutMillis = 500,
      sendJson = (_, _, _) => scala.sys.error("history down")
    )

    noException should be thrownBy bridge.publish(
      AppEvent.GameFinished(sid, gid, GameStatus.Draw(DrawReason.Stalemate))
    )
  }
