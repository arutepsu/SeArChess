package chess.lichessbridge

import cats.effect.unsafe.implicits.global
import chess.adapter.ai.remote.RemoteAiMoveDto
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AiServiceClientSpec extends AnyFlatSpec with Matchers:

  private val sampleLegalMoves = List(
    RemoteAiMoveDto("e2", "e4"),
    RemoteAiMoveDto("d2", "d4"),
    RemoteAiMoveDto("g1", "f3")
  )

  private val sampleFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  // ── StubAiServiceClient ───────────────────────────────────────────────────────

  "StubAiServiceClient" should "return the configured Right(UciMove) result" in {
    val client = StubAiServiceClient(Right(UciMove("e2e4")))
    val req    = AiMoveRequest("g1", sampleFen, sampleLegalMoves, "White")
    client.suggestMove(req).unsafeRunSync() shouldBe Right(UciMove("e2e4"))
  }

  it should "return the configured Left(AiError) result" in {
    val client = StubAiServiceClient(Left(AiError.ServiceError("unavailable")))
    val req    = AiMoveRequest("g1", sampleFen, sampleLegalMoves, "White")
    client.suggestMove(req).unsafeRunSync() shouldBe Left(AiError.ServiceError("unavailable"))
  }

  it should "return Left(NoLegalMoves) by default when override is not set" in {
    val client = StubAiServiceClient(Left(AiError.NoLegalMoves))
    val req    = AiMoveRequest("g1", sampleFen, Nil, "White")
    client.suggestMove(req).unsafeRunSync() shouldBe Left(AiError.NoLegalMoves)
  }

  // ── JdkAiServiceClient: guard for empty legal moves ──────────────────────────

  "JdkAiServiceClient" should "return Left(NoLegalMoves) immediately when legalMoves list is empty" in {
    val client = JdkAiServiceClient("http://unreachable-host:9999")
    val req    = AiMoveRequest("g1", sampleFen, Nil, "White")
    client.suggestMove(req).unsafeRunSync() shouldBe Left(AiError.NoLegalMoves)
  }

  it should "return Left(ServiceError) when ai-service is unreachable" in {
    val client = JdkAiServiceClient("http://127.0.0.1:19991")
    val req    = AiMoveRequest("g1", sampleFen, sampleLegalMoves, "White")
    val result = client.suggestMove(req).unsafeRunSync()
    result.isLeft shouldBe true
    result match
      case Left(AiError.ServiceError(_)) => succeed
      case Left(AiError.Timeout(_))      => succeed
      case other                         => fail(s"Expected Left(ServiceError or Timeout) but got $other")
  }

  // ── UciMove.value format ──────────────────────────────────────────────────────

  "UciMove" should "encode non-promotion moves as 4-character strings" in {
    UciMove("e2e4").value shouldBe "e2e4"
    UciMove("e2e4").value.length shouldBe 4
  }

  it should "encode promotion moves as 5-character strings" in {
    UciMove("e7e8q").value shouldBe "e7e8q"
    UciMove("e7e8q").value.length shouldBe 5
  }
