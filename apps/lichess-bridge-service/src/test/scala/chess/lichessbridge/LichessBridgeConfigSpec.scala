package chess.lichessbridge

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LichessBridgeConfigSpec extends AnyFlatSpec with Matchers:

  private def env(pairs: (String, String)*): String => Option[String] =
    val m = pairs.toMap
    key => m.get(key)

  "LichessBridgeConfig.load" should "use defaults when no env vars are set" in {
    val result = LichessBridgeConfig.load(env())
    result shouldBe Right(LichessBridgeConfig(
      enabled            = false,
      lichessApiBaseUrl  = "https://lichess.org",
      lichessBotUsername = None,
      lichessBotToken    = None,
      aiServiceUrl       = "http://ai-service:8765",
      maxConcurrentGames = 1,
      host               = "0.0.0.0",
      port               = 8090
    ))
  }

  it should "read LICHESS_BRIDGE_ENABLED as true when set to 'true'" in {
    val result = LichessBridgeConfig.load(env("LICHESS_BRIDGE_ENABLED" -> "true"))
    result.map(_.enabled) shouldBe Right(true)
  }

  it should "treat any value other than 'true' as disabled" in {
    val result = LichessBridgeConfig.load(env("LICHESS_BRIDGE_ENABLED" -> "yes"))
    result.map(_.enabled) shouldBe Right(false)
  }

  it should "read LICHESS_BOT_USERNAME when present" in {
    val result = LichessBridgeConfig.load(env("LICHESS_BOT_USERNAME" -> "searchess-bot"))
    result.map(_.lichessBotUsername) shouldBe Right(Some("searchess-bot"))
  }

  it should "parse a custom port" in {
    val result = LichessBridgeConfig.load(env("LICHESS_BRIDGE_HTTP_PORT" -> "9090"))
    result.map(_.port) shouldBe Right(9090)
  }

  it should "return Left for an out-of-range port" in {
    val result = LichessBridgeConfig.load(env("LICHESS_BRIDGE_HTTP_PORT" -> "99999"))
    result.isLeft shouldBe true
  }

  it should "return Left for a non-integer port" in {
    val result = LichessBridgeConfig.load(env("LICHESS_BRIDGE_HTTP_PORT" -> "notaport"))
    result.isLeft shouldBe true
  }

  it should "read MAX_CONCURRENT_GAMES" in {
    val result = LichessBridgeConfig.load(env("MAX_CONCURRENT_GAMES" -> "3"))
    result.map(_.maxConcurrentGames) shouldBe Right(3)
  }

  // Phase 2A: token and tokenConfigured tests

  it should "have tokenConfigured=false when LICHESS_BOT_TOKEN is absent" in {
    val result = LichessBridgeConfig.load(env())
    result.map(_.tokenConfigured) shouldBe Right(false)
  }

  it should "have tokenConfigured=false when LICHESS_BOT_TOKEN is empty string" in {
    // The default env implementation filters .nonEmpty — simulate that by providing an
    // env function that behaves identically: empty string maps to None.
    val envWithEmpty: String => Option[String] =
      key => if key == "LICHESS_BOT_TOKEN" then Some("").filter(_.nonEmpty) else None
    val result = LichessBridgeConfig.load(envWithEmpty)
    result.map(_.tokenConfigured) shouldBe Right(false)
  }

  it should "have tokenConfigured=true when LICHESS_BOT_TOKEN has a value" in {
    val result = LichessBridgeConfig.load(env("LICHESS_BOT_TOKEN" -> "lip_secret"))
    result.map(_.tokenConfigured) shouldBe Right(true)
  }

  it should "have botUsernameConfigured=false when LICHESS_BOT_USERNAME is absent" in {
    val result = LichessBridgeConfig.load(env())
    result.map(_.botUsernameConfigured) shouldBe Right(false)
  }

  it should "have botUsernameConfigured=true when LICHESS_BOT_USERNAME is set" in {
    val result = LichessBridgeConfig.load(env("LICHESS_BOT_USERNAME" -> "searchess-bot"))
    result.map(_.botUsernameConfigured) shouldBe Right(true)
  }

  // requireToken() tests

  it should "requireToken() returns Left when enabled=true and no token" in {
    val result = LichessBridgeConfig.load(env("LICHESS_BRIDGE_ENABLED" -> "true"))
    result.map(_.requireToken()) shouldBe Right(Left("Token required when bridge is enabled"))
  }

  it should "requireToken() returns Right(token) when enabled=true and token present" in {
    val result = LichessBridgeConfig.load(env(
      "LICHESS_BRIDGE_ENABLED" -> "true",
      "LICHESS_BOT_TOKEN"      -> "lip_secret"
    ))
    result.map(_.requireToken()) shouldBe Right(Right("lip_secret"))
  }

  it should "requireToken() returns Right when disabled and no token" in {
    val result = LichessBridgeConfig.load(env())
    result.map(_.requireToken()) shouldBe Right(Right(""))
  }

  it should "requireToken() returns Right(token) when disabled but token present" in {
    val result = LichessBridgeConfig.load(env("LICHESS_BOT_TOKEN" -> "lip_secret"))
    result.map(_.requireToken()) shouldBe Right(Right("lip_secret"))
  }

  // Phase 2B-1: challenge policy config tests

  it should "have acceptChallenges=false by default" in {
    val result = LichessBridgeConfig.load(env())
    result.map(_.acceptChallenges) shouldBe Right(false)
  }

  it should "read LICHESS_ACCEPT_CHALLENGES=true" in {
    val result = LichessBridgeConfig.load(env("LICHESS_ACCEPT_CHALLENGES" -> "true"))
    result.map(_.acceptChallenges) shouldBe Right(true)
  }

  it should "have acceptRated=false by default" in {
    val result = LichessBridgeConfig.load(env())
    result.map(_.acceptRated) shouldBe Right(false)
  }

  it should "read LICHESS_ACCEPT_RATED=true" in {
    val result = LichessBridgeConfig.load(env("LICHESS_ACCEPT_RATED" -> "true"))
    result.map(_.acceptRated) shouldBe Right(true)
  }

  it should "have allowedVariants=Set(standard) by default" in {
    val result = LichessBridgeConfig.load(env())
    result.map(_.allowedVariants) shouldBe Right(Set("standard"))
  }

  it should "parse LICHESS_ALLOWED_VARIANTS comma-separated" in {
    val result = LichessBridgeConfig.load(env("LICHESS_ALLOWED_VARIANTS" -> "standard,chess960"))
    result.map(_.allowedVariants) shouldBe Right(Set("standard", "chess960"))
  }

  it should "have minClockSeconds=180 by default" in {
    val result = LichessBridgeConfig.load(env())
    result.map(_.minClockSeconds) shouldBe Right(180)
  }

  it should "read LICHESS_MIN_CLOCK_SECONDS" in {
    val result = LichessBridgeConfig.load(env("LICHESS_MIN_CLOCK_SECONDS" -> "60"))
    result.map(_.minClockSeconds) shouldBe Right(60)
  }

  it should "have maxClockSeconds=600 by default" in {
    val result = LichessBridgeConfig.load(env())
    result.map(_.maxClockSeconds) shouldBe Right(600)
  }

  it should "read LICHESS_MAX_CLOCK_SECONDS" in {
    val result = LichessBridgeConfig.load(env("LICHESS_MAX_CLOCK_SECONDS" -> "900"))
    result.map(_.maxClockSeconds) shouldBe Right(900)
  }

  it should "have allowedChallengers=empty by default" in {
    val result = LichessBridgeConfig.load(env())
    result.map(_.allowedChallengers) shouldBe Right(Set.empty[String])
  }

  it should "parse LICHESS_ALLOWED_CHALLENGERS comma-separated, lowercased" in {
    val result = LichessBridgeConfig.load(env("LICHESS_ALLOWED_CHALLENGERS" -> "Alice, Bob, CAROL"))
    result.map(_.allowedChallengers) shouldBe Right(Set("alice", "bob", "carol"))
  }
