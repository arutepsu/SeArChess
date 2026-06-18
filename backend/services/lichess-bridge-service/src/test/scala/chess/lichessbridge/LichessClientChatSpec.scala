package chess.lichessbridge

import cats.effect.unsafe.implicits.global
import chess.lichessbridge.LichessError.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LichessClientChatSpec extends AnyFlatSpec with Matchers:

  "ControllableLichessClient.sendChatMessage" should "return Right(()) by default" in {
    val client = ControllableLichessClient()
    client.sendChatMessage("tok", "g1", "hello").unsafeRunSync() shouldBe Right(())
  }

  it should "return configured sendChatResult" in {
    val err    = Left(NetworkError("chat unavailable"))
    val client = ControllableLichessClient(sendChatResult = err)
    client.sendChatMessage("tok", "g1", "hello").unsafeRunSync() shouldBe err
  }

  "StubLichessClient.sendChatMessage" should "return Left(NetworkError)" in {
    val client = StubLichessClient()
    client.sendChatMessage("tok", "g1", "hello").unsafeRunSync() shouldBe
      Left(NetworkError("StubLichessClient: not implemented"))
  }
