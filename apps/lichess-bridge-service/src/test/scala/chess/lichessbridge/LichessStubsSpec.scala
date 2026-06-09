package chess.lichessbridge

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.lichessbridge.LichessError.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LichessStubsSpec extends AnyFlatSpec with Matchers:

  // ── StubLichessClient ─────────────────────────────────────────────────────────

  "StubLichessClient" should "return Left NetworkError from getBotProfile" in {
    StubLichessClient().getBotProfile("any-token").unsafeRunSync() shouldBe
      Left(NetworkError("StubLichessClient: not implemented"))
  }

  it should "return Left from validateToken" in {
    StubLichessClient().validateToken("any-token").unsafeRunSync().isLeft shouldBe true
  }

  it should "return Left from challengeAi" in {
    StubLichessClient().challengeAi("any-token").unsafeRunSync().isLeft shouldBe true
  }

  it should "return Left from acceptChallenge" in {
    StubLichessClient().acceptChallenge("any-token", "ch1").unsafeRunSync().isLeft shouldBe true
  }

  it should "return Left from declineChallenge" in {
    StubLichessClient().declineChallenge("any-token", "ch1", "generic").unsafeRunSync().isLeft shouldBe true
  }

  // ── ControllableLichessClient ─────────────────────────────────────────────────

  "ControllableLichessClient" should "return the configured validateResult" in {
    val client = ControllableLichessClient(validateResult = Right(true))
    client.validateToken("t").unsafeRunSync() shouldBe Right(true)
  }

  it should "return the configured acceptResult" in {
    val client = ControllableLichessClient(acceptResult = Right(()))
    client.acceptChallenge("t", "ch1").unsafeRunSync() shouldBe Right(())
  }

  it should "return the configured declineResult" in {
    val client = ControllableLichessClient(declineResult = Left(Unauthorized("rejected")))
    client.declineChallenge("t", "ch1", "generic").unsafeRunSync() shouldBe
      Left(Unauthorized("rejected"))
  }

  // ── StubLichessEventStream ────────────────────────────────────────────────────

  "StubLichessEventStream" should "emit no events by default" in {
    StubLichessEventStream()
      .streamBotEvents("t")
      .compile
      .toList
      .unsafeRunSync() shouldBe empty
  }

  it should "emit the provided events" in {
    val event  = LichessBotEvent.ChallengeCanceled("ch1")
    val events = List(Right(event))
    StubLichessEventStream(events)
      .streamBotEvents("t")
      .compile
      .toList
      .unsafeRunSync() shouldBe events
  }

  // ── DisconnectingLichessEventStream ───────────────────────────────────────────

  "DisconnectingLichessEventStream" should "raise the error after emitting events" in {
    val event   = Right(LichessBotEvent.ChallengeCanceled("ch1"))
    val stream  = DisconnectingLichessEventStream(List(event), LichessNetworkException("reset"))
    val result  = stream.streamBotEvents("t").attempt.compile.toList.unsafeRunSync()
    result.head shouldBe Right(event)
    result.last.isLeft shouldBe true
  }

  // ── AcceptAllChallengePolicy ──────────────────────────────────────────────────

  "AcceptAllChallengePolicy" should "always return Accept" in {
    val challenge = LichessChallenge("c1", "p", "standard", "blitz", false,
      LichessTimeControl("clock", Some(300), Some(0)), None)
    AcceptAllChallengePolicy().evaluate(challenge).unsafeRunSync() shouldBe ChallengeDecision.Accept
  }

  // ── DeclineAllChallengePolicy ─────────────────────────────────────────────────

  "DeclineAllChallengePolicy" should "always return Decline with the configured reason" in {
    val challenge = LichessChallenge("c1", "p", "standard", "blitz", false,
      LichessTimeControl("clock", Some(300), Some(0)), None)
    DeclineAllChallengePolicy(DeclineReason.MaxGamesReached)
      .evaluate(challenge)
      .unsafeRunSync() shouldBe ChallengeDecision.Decline(DeclineReason.MaxGamesReached)
  }
