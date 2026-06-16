package chess.arena.bots.ai

import chess.adapter.ai.remote.{RemoteAiJson, RemoteAiMoveDto, RemoteAiMoveSuggestionResponse}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HttpSearchessAiClientSpec extends AnyFlatSpec with Matchers:

  // Tests here verify JSON codec behaviour; no live HTTP is required.

  "RemoteAiJson.responseFromJson" should "parse a minimal success response" in {
    val json   = """{"requestId":"req-1","move":{"from":"e2","to":"e4"}}"""
    val result = RemoteAiJson.responseFromJson(json)
    result shouldBe Right(
      RemoteAiMoveSuggestionResponse(requestId = "req-1", move = RemoteAiMoveDto("e2", "e4"))
    )
  }

  it should "parse a promotion response" in {
    val json   = """{"requestId":"req-2","move":{"from":"d7","to":"d8","promotion":"q"}}"""
    val result = RemoteAiJson.responseFromJson(json)
    result.map(_.move.promotion) shouldBe Right(Some("q"))
  }

  it should "return Left for malformed JSON" in {
    val result = RemoteAiJson.responseFromJson("{not-json")
    result.isLeft shouldBe true
  }

  it should "return Left when 'move' field is absent" in {
    val json   = """{"requestId":"req-3"}"""
    val result = RemoteAiJson.responseFromJson(json)
    result.isLeft shouldBe true
  }

  "RemoteAiJson.errorFromJson" should "parse a valid error response" in {
    val json   = """{"requestId":"req-4","code":"BAD_REQUEST","message":"legalMoves must not be empty"}"""
    val result = RemoteAiJson.errorFromJson(json)
    result.isDefined     shouldBe true
    result.map(_.code)   shouldBe Some("BAD_REQUEST")
  }

  it should "return None when 'code' field is absent" in {
    val json   = """{"requestId":"req-5","move":{"from":"e2","to":"e4"}}"""
    val result = RemoteAiJson.errorFromJson(json)
    result shouldBe None
  }

  "SearchessAiClientError.describe" should "produce a readable message for each case" in {
    SearchessAiClientError.ConnectionFailed("refused").describe should include("Connection failed")
    SearchessAiClientError.Timeout("timeout").describe          should include("timed out")
    SearchessAiClientError.BadResponse(503, "").describe        should include("503")
    SearchessAiClientError.ParseError("bad json").describe      should include("parse error")
  }
