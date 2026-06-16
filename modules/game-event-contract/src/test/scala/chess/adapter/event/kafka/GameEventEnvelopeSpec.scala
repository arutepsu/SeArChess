package chess.adapter.event.kafka

import chess.application.event.AppEvent
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.application.session.model.{SessionMode, SideController}
import chess.domain.model.{Color, Move, Position}
import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID

class GameEventEnvelopeSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  private val sessionId = SessionId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
  private val gameId    = GameId(UUID.fromString("22222222-2222-2222-2222-222222222222"))

  "GameEventEnvelope" should "wrap GameCreated in the common envelope" in {
    val event = AppEvent.SessionCreated(
      sessionId = sessionId,
      gameId = gameId,
      mode = SessionMode.HumanVsHuman,
      whiteController = SideController.HumanLocal,
      blackController = SideController.HumanLocal
    )

    val envelope = GameEventEnvelope.fromAppEvent(event).value

    envelope.eventType shouldBe GameEventEnvelope.GameCreated
    envelope.eventVersion shouldBe EventEnvelope.SupportedVersion
    envelope.producer shouldBe "game-service"
    envelope.aggregateType shouldBe "Game"
    envelope.aggregateId shouldBe gameId.value.toString
    envelope.payload("gameId").str shouldBe gameId.value.toString
  }

  it should "wrap MoveApplied with game id as aggregate id for Kafka partitioning" in {
    val event = AppEvent.MoveApplied(
      sessionId = sessionId,
      gameId = gameId,
      move = Move(pos("e2"), pos("e4")),
      playerWhoMoved = Color.White
    )

    val envelope = GameEventEnvelope.fromAppEvent(event).value
    val json = EventEnvelope.writePayloadEnvelope(envelope)
    val decoded = EventEnvelope.readPayloadEnvelope(json).flatMap(GameEventEnvelope.validate).value

    decoded.eventType shouldBe GameEventEnvelope.MoveApplied
    decoded.aggregateId shouldBe gameId.value.toString
    decoded.payload("move")("from").str shouldBe "e2"
    decoded.payload("move")("to").str shouldBe "e4"
  }

  it should "reject unsupported event versions" in {
    val event = AppEvent.MoveRejected(
      sessionId = sessionId,
      gameId = gameId,
      move = Move(pos("e2"), pos("e4")),
      reason = "illegal"
    )
    val envelope = GameEventEnvelope.fromAppEvent(event).value.copy(eventVersion = 2)

    GameEventEnvelope.validate(envelope).left.value should include("unsupported eventVersion")
  }

  private def pos(algebraic: String): Position =
    Position.fromAlgebraic(algebraic).value
