package chess.lichessbridge

import chess.lichessbridge.LichessError.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests the pure JSON parsing helpers in LichessHttpClient companion object.
  *
  * No HTTP calls are made. All tests are fully deterministic.
  */
class LichessHttpClientParseSpec extends AnyFlatSpec with Matchers:

  // ── parseProfile ─────────────────────────────────────────────────────────────

  "LichessHttpClient.parseProfile" should "parse a BOT profile correctly" in {
    val json = """{"id":"searchess-bot","username":"SearchessBot","title":"BOT","perfs":{}}"""
    LichessHttpClient.parseProfile(json) shouldBe
      Right(BotProfile("searchess-bot", "SearchessBot", Some("BOT"), isBot = true))
  }

  it should "parse a non-BOT profile (no title)" in {
    val json = """{"id":"normaluser","username":"NormalUser","perfs":{}}"""
    LichessHttpClient.parseProfile(json) shouldBe
      Right(BotProfile("normaluser", "NormalUser", None, isBot = false))
  }

  it should "set isBot=false for non-BOT titles" in {
    val json = """{"id":"gm-player","username":"GmPlayer","title":"GM"}"""
    LichessHttpClient.parseProfile(json) shouldBe
      Right(BotProfile("gm-player", "GmPlayer", Some("GM"), isBot = false))
  }

  it should "return Left on malformed JSON" in {
    LichessHttpClient.parseProfile("not json").isLeft shouldBe true
  }

  it should "return Left when required fields are missing" in {
    LichessHttpClient.parseProfile("""{"username":"NoId"}""").isLeft shouldBe true
  }

  // ── parseChallenge ────────────────────────────────────────────────────────────

  "LichessHttpClient.parseChallenge" should "parse a root-level id and url" in {
    val json = """{"id":"game123","url":"https://lichess.org/game123","color":"white"}"""
    LichessHttpClient.parseChallenge(json) shouldBe
      Right(ChallengeResult("game123", "https://lichess.org/game123"))
  }

  it should "parse a response nested under the 'challenge' key" in {
    val json = """{"challenge":{"id":"game456","url":"https://lichess.org/game456"}}"""
    LichessHttpClient.parseChallenge(json) shouldBe
      Right(ChallengeResult("game456", "https://lichess.org/game456"))
  }

  it should "fall back to a constructed URL when url field is absent" in {
    val json = """{"id":"game789"}"""
    LichessHttpClient.parseChallenge(json) shouldBe
      Right(ChallengeResult("game789", "https://lichess.org/game789"))
  }

  it should "return Left when game id is absent" in {
    LichessHttpClient.parseChallenge("""{"url":"https://lichess.org/x"}""").isLeft shouldBe true
  }

  it should "return Left on malformed JSON" in {
    LichessHttpClient.parseChallenge("not json").isLeft shouldBe true
  }

  // ── mapStatus ─────────────────────────────────────────────────────────────────

  "LichessHttpClient.mapStatus" should "return Unauthorized for 401" in {
    LichessHttpClient.mapStatus(401, "Unauthorized", None) shouldBe Left(Unauthorized("Token rejected by Lichess."))
  }

  it should "return Unauthorized for 403" in {
    LichessHttpClient.mapStatus(403, "Forbidden", None) shouldBe Left(Unauthorized("Token rejected by Lichess."))
  }

  it should "return RateLimited for 429 without header" in {
    LichessHttpClient.mapStatus(429, "Too many requests", None) shouldBe Left(RateLimited(None))
  }

  it should "return RateLimited for 429 with Retry-After" in {
    LichessHttpClient.mapStatus(429, "Too many requests", Some(60)) shouldBe Left(RateLimited(Some(60)))
  }

  it should "return UnexpectedResponse for 500" in {
    LichessHttpClient.mapStatus(500, "server error", None) match
      case Left(UnexpectedResponse(500, _)) => succeed
      case other                            => fail(s"Expected UnexpectedResponse(500,...) but got $other")
  }
