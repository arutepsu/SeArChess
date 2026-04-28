package chess.server.config

import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConfigLoaderSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  private val postgresEnv = Seq(
    "SEARCHESS_POSTGRES_URL" -> "jdbc:postgresql://localhost:5432/searchess",
    "SEARCHESS_POSTGRES_USER" -> "searchess",
    "SEARCHESS_POSTGRES_PASSWORD" -> "searchess"
  )

  private def load(values: (String, String)*): Either[String, AppConfig] =
    val env = values.toMap
    ConfigLoader.loadFrom(key => env.get(key))

  private def loadDefault(values: (String, String)*): Either[String, AppConfig] =
    load((postgresEnv ++ values)*)

  "ConfigLoader" should "default to the remote AI client" in {
    val config = loadDefault().value

    config.ai.mode shouldBe AiProviderMode.Remote
    config.ai.remote.value.baseUrl shouldBe "http://ai-service:8765"
    config.ai.timeoutMillis shouldBe 2000
    config.ai.defaultEngineId shouldBe None
    config.ai.interaction shouldBe ServiceInteraction.InternalSynchronousHttp
    config.ai.startupPolicy shouldBe DependencyStartupPolicy.NotRequired
    config.ai.failureBehaviour shouldBe DependencyFailureBehaviour.FailRequest
  }

  it should "parse explicit local deterministic AI mode as transitional fallback" in {
    val config = loadDefault("AI_PROVIDER_MODE" -> "local").value

    config.ai.mode shouldBe AiProviderMode.LocalDeterministic
    config.ai.remote shouldBe None
  }

  it should "default History forwarding to disabled" in {
    val config = loadDefault().value

    config.history.enabled shouldBe false
    config.history.baseUrl shouldBe None
    config.history.timeoutMillis shouldBe 2000
    config.history.interaction shouldBe ServiceInteraction.DownstreamAsynchronousHttp
    config.history.startupPolicy shouldBe DependencyStartupPolicy.NotRequired
    config.history.failureBehaviour shouldBe DependencyFailureBehaviour.LogAndContinue
  }

  it should "parse enabled History forwarding with base URL and timeout" in {
    val config = loadDefault(
      "HISTORY_FORWARDING_ENABLED" -> "true",
      "HISTORY_SERVICE_BASE_URL" -> "http://history-service:8081",
      "HISTORY_FORWARDING_TIMEOUT_MILLIS" -> "1500"
    ).value

    config.history.enabled shouldBe true
    config.history.baseUrl.value shouldBe "http://history-service:8081"
    config.history.timeoutMillis shouldBe 1500
  }

  it should "reject enabled History forwarding without a base URL" in {
    loadDefault("HISTORY_FORWARDING_ENABLED" -> "true").left.value should include(
      "HISTORY_SERVICE_BASE_URL is required"
    )
  }

  it should "parse disabled AI mode" in {
    val config = loadDefault("AI_PROVIDER_MODE" -> "disabled").value

    config.ai.mode shouldBe AiProviderMode.Disabled
  }

  it should "parse remote AI mode with base URL, timeout, and default engine id" in {
    val config = loadDefault(
      "AI_PROVIDER_MODE" -> "remote",
      "AI_REMOTE_BASE_URL" -> "http://ai.local:9000",
      "AI_TIMEOUT_MILLIS" -> "3500",
      "AI_DEFAULT_ENGINE_ID" -> "stockfish-default",
      "AI_REMOTE_TEST_MODE" -> "illegal_move"
    ).value

    config.ai.mode shouldBe AiProviderMode.Remote
    config.ai.remote.value.baseUrl shouldBe "http://ai.local:9000"
    config.ai.remote.value.testMode.value shouldBe "illegal_move"
    config.ai.timeoutMillis shouldBe 3500
    config.ai.defaultEngineId.value shouldBe "stockfish-default"
  }

  it should "use the default compose AI service URL when remote mode omits a base URL" in {
    val config = loadDefault("AI_PROVIDER_MODE" -> "remote").value

    config.ai.remote.value.baseUrl shouldBe "http://ai-service:8765"
  }

  it should "reject non-positive AI timeout" in {
    loadDefault("AI_TIMEOUT_MILLIS" -> "0").left.value should include("AI_TIMEOUT_MILLIS must be >= 1")
  }

  it should "default to Postgres persistence with Postgres config" in {
    val config = loadDefault().value
    config.persistence shouldBe PersistenceMode.Postgres
    config.sqlite shouldBe None
    config.postgres.value.url shouldBe "jdbc:postgresql://localhost:5432/searchess"
    config.postgres.value.user shouldBe "searchess"
    config.postgres.value.password shouldBe "searchess"
  }

  it should "reject missing Postgres env vars with a helpful default-backend message" in {
    val error = load().left.value

    error should include("Postgres is the default game-service persistence backend")
    error should include("SEARCHESS_POSTGRES_URL")
    error should include("SEARCHESS_POSTGRES_USER")
    error should include("SEARCHESS_POSTGRES_PASSWORD")
    error should include("docker compose -f docker-compose.persistence.yml up -d")
    error should include("PERSISTENCE_MODE=sqlite")
    error should include("PERSISTENCE_MODE=in-memory")
  }

  it should "require Postgres user and password for runtime Postgres persistence" in {
    val error = load("SEARCHESS_POSTGRES_URL" -> "jdbc:postgresql://localhost:5432/searchess").left.value

    error should include("SEARCHESS_POSTGRES_USER")
    error should include("SEARCHESS_POSTGRES_PASSWORD")
    error should include("Postgres is the default game-service persistence backend")
  }

  it should "parse postgres persistence mode explicitly" in {
    val config = loadDefault("PERSISTENCE_MODE" -> "postgres").value
    config.persistence shouldBe PersistenceMode.Postgres
    config.postgres.value.user shouldBe "searchess"
  }

  it should "parse postgresql persistence mode as an alias" in {
    val config = loadDefault("PERSISTENCE_MODE" -> "postgresql").value
    config.persistence shouldBe PersistenceMode.Postgres
    config.postgres.value.url should include("jdbc:postgresql")
  }

  it should "parse in-memory persistence mode" in {
    val config = load("PERSISTENCE_MODE" -> "in-memory").value
    config.persistence shouldBe PersistenceMode.InMemory
    config.postgres shouldBe None
  }

  it should "parse inmemory persistence mode as an alias" in {
    val config = load("PERSISTENCE_MODE" -> "inmemory").value
    config.persistence shouldBe PersistenceMode.InMemory
    config.postgres shouldBe None
  }

  it should "parse sqlite persistence mode and populate SqliteConfig with default path" in {
    val config = load("PERSISTENCE_MODE" -> "sqlite").value
    config.persistence shouldBe PersistenceMode.SQLite
    config.sqlite.value.path shouldBe "chess.db"
    config.postgres shouldBe None
  }

  it should "parse sqlite persistence mode with a custom CHESS_DB_PATH" in {
    val config = load("PERSISTENCE_MODE" -> "sqlite", "CHESS_DB_PATH" -> "/data/game.db").value
    config.sqlite.value.path shouldBe "/data/game.db"
  }

  it should "reject unknown persistence mode" in {
    load("PERSISTENCE_MODE" -> "flat-file").left.value should include("PERSISTENCE_MODE")
  }
