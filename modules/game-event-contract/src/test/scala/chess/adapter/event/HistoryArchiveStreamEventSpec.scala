package chess.adapter.event

import chess.application.event.AppEvent
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, GameStatus}
import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID

class HistoryArchiveStreamEventSpec extends AnyFlatSpec with Matchers with OptionValues with EitherValues:

  private val sid = SessionId(UUID.fromString("00000000-0000-0000-0000-000000000101"))
  private val gid = GameId(UUID.fromString("00000000-0000-0000-0000-000000000102"))

  "HistoryArchiveStreamEvent" should "create and parse an archive requested envelope" in {
    val event = AppEvent.GameFinished(sid, gid, GameStatus.Checkmate(Color.White))
    val envelope = HistoryArchiveStreamEvent
      .fromAppEvent(event, Instant.parse("2026-05-22T10:00:00Z"))
      .value

    envelope.eventType shouldBe "history.archive.requested"
    envelope.eventVersion shouldBe 1
    envelope.gameId shouldBe gid
    envelope.payloadJson should include("game.finished.v1")

    val fields = HistoryArchiveStreamEvent.toFields(envelope)
    fields.get("sourceEventType") shouldBe "game.finished.v1"
    HistoryArchiveStreamEvent.fromFields(fields) shouldBe Right(envelope)
  }

  it should "ignore non-terminal game events" in {
    HistoryArchiveStreamEvent.fromAppEvent(AppEvent.AITurnRequested(sid, gid, Color.White)) shouldBe None
  }

  it should "reject envelopes whose gameId differs from payloadJson" in {
    val envelope = HistoryArchiveStreamEvent
      .fromAppEvent(AppEvent.SessionCancelled(sid, gid), Instant.parse("2026-05-22T10:00:00Z"))
      .value
      .copy(gameId = GameId(UUID.fromString("00000000-0000-0000-0000-000000000999")))

    HistoryArchiveStreamEvent.fromFields(HistoryArchiveStreamEvent.toFields(envelope)).left.value should include(
      "gameId does not match payloadJson"
    )
  }
