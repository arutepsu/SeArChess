package chess.config

import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConfigLoaderSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  private def load(values: (String, String)*): Either[String, AppConfig] =
    val env = values.toMap
    ConfigLoader.loadFrom(key => env.get(key))

  "ConfigLoader" should "default to the local deterministic AI provider" in {
    val config = load().value

    config.ai.mode            shouldBe AiProviderMode.LocalDeterministic
    config.ai.remote          shouldBe None
    config.ai.timeoutMillis   shouldBe 2000
    config.ai.defaultEngineId shouldBe None
  }

  it should "parse disabled AI mode" in {
    val config = load("AI_PROVIDER_MODE" -> "disabled").value

    config.ai.mode shouldBe AiProviderMode.Disabled
  }

  it should "parse remote AI mode with base URL, timeout, and default engine id" in {
    val config = load(
      "AI_PROVIDER_MODE"    -> "remote",
      "AI_REMOTE_BASE_URL"  -> "http://ai.local:9000",
      "AI_TIMEOUT_MILLIS"   -> "3500",
      "AI_DEFAULT_ENGINE_ID" -> "stockfish-default"
    ).value

    config.ai.mode                       shouldBe AiProviderMode.Remote
    config.ai.remote.value.baseUrl       shouldBe "http://ai.local:9000"
    config.ai.timeoutMillis              shouldBe 3500
    config.ai.defaultEngineId.value      shouldBe "stockfish-default"
  }

  it should "reject remote AI mode without a base URL" in {
    load("AI_PROVIDER_MODE" -> "remote").left.value should include ("AI_REMOTE_BASE_URL is required")
  }

  it should "reject non-positive AI timeout" in {
    load("AI_TIMEOUT_MILLIS" -> "0").left.value should include ("AI_TIMEOUT_MILLIS must be >= 1")
  }
