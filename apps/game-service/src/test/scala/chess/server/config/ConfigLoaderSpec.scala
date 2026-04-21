package chess.server.config

import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConfigLoaderSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  private def load(values: (String, String)*): Either[String, AppConfig] =
    val env = values.toMap
    ConfigLoader.loadFrom(key => env.get(key))

  "ConfigLoader" should "default to the remote AI client" in {
    val config = load().value

    config.ai.mode                  shouldBe AiProviderMode.Remote
    config.ai.remote.value.baseUrl  shouldBe "http://ai-service:8765"
    config.ai.timeoutMillis         shouldBe 2000
    config.ai.defaultEngineId       shouldBe None
    config.ai.interaction           shouldBe ServiceInteraction.InternalSynchronousHttp
    config.ai.startupPolicy         shouldBe DependencyStartupPolicy.NotRequired
    config.ai.failureBehaviour      shouldBe DependencyFailureBehaviour.FailRequest
  }

  it should "parse explicit local deterministic AI mode as transitional fallback" in {
    val config = load("AI_PROVIDER_MODE" -> "local").value

    config.ai.mode   shouldBe AiProviderMode.LocalDeterministic
    config.ai.remote shouldBe None
  }

  it should "default History forwarding to disabled" in {
    val config = load().value

    config.history.enabled       shouldBe false
    config.history.baseUrl       shouldBe None
    config.history.timeoutMillis shouldBe 2000
    config.history.interaction   shouldBe ServiceInteraction.DownstreamAsynchronousHttp
    config.history.startupPolicy shouldBe DependencyStartupPolicy.NotRequired
    config.history.failureBehaviour shouldBe DependencyFailureBehaviour.LogAndContinue
  }

  it should "parse enabled History forwarding with base URL and timeout" in {
    val config = load(
      "HISTORY_FORWARDING_ENABLED"        -> "true",
      "HISTORY_SERVICE_BASE_URL"          -> "http://history-service:8081",
      "HISTORY_FORWARDING_TIMEOUT_MILLIS" -> "1500"
    ).value

    config.history.enabled       shouldBe true
    config.history.baseUrl.value shouldBe "http://history-service:8081"
    config.history.timeoutMillis shouldBe 1500
  }

  it should "reject enabled History forwarding without a base URL" in {
    load("HISTORY_FORWARDING_ENABLED" -> "true").left.value should include ("HISTORY_SERVICE_BASE_URL is required")
  }

  it should "parse disabled AI mode" in {
    val config = load("AI_PROVIDER_MODE" -> "disabled").value

    config.ai.mode shouldBe AiProviderMode.Disabled
  }

  it should "parse remote AI mode with base URL, timeout, and default engine id" in {
    val config = load(
      "AI_PROVIDER_MODE"     -> "remote",
      "AI_REMOTE_BASE_URL"   -> "http://ai.local:9000",
      "AI_TIMEOUT_MILLIS"    -> "3500",
      "AI_DEFAULT_ENGINE_ID" -> "stockfish-default",
      "AI_REMOTE_TEST_MODE"  -> "illegal_move"
    ).value

    config.ai.mode                        shouldBe AiProviderMode.Remote
    config.ai.remote.value.baseUrl        shouldBe "http://ai.local:9000"
    config.ai.remote.value.testMode.value shouldBe "illegal_move"
    config.ai.timeoutMillis               shouldBe 3500
    config.ai.defaultEngineId.value       shouldBe "stockfish-default"
  }

  it should "use the default compose AI service URL when remote mode omits a base URL" in {
    val config = load("AI_PROVIDER_MODE" -> "remote").value

    config.ai.remote.value.baseUrl shouldBe "http://ai-service:8765"
  }

  it should "reject non-positive AI timeout" in {
    load("AI_TIMEOUT_MILLIS" -> "0").left.value should include ("AI_TIMEOUT_MILLIS must be >= 1")
  }

  it should "default to InMemory persistence with no sqlite config" in {
    val config = load().value
    config.persistence shouldBe PersistenceMode.InMemory
    config.sqlite      shouldBe None
  }

  it should "parse sqlite persistence mode and populate SqliteConfig with default path" in {
    val config = load("PERSISTENCE_MODE" -> "sqlite").value
    config.persistence       shouldBe PersistenceMode.SQLite
    config.sqlite.value.path shouldBe "chess.db"
  }

  it should "parse sqlite persistence mode with a custom CHESS_DB_PATH" in {
    val config = load("PERSISTENCE_MODE" -> "sqlite", "CHESS_DB_PATH" -> "/data/game.db").value
    config.sqlite.value.path shouldBe "/data/game.db"
  }

  it should "reject unknown persistence mode" in {
    load("PERSISTENCE_MODE" -> "postgres").left.value should include ("PERSISTENCE_MODE")
  }
