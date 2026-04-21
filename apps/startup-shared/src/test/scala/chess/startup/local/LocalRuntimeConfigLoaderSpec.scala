package chess.startup.local

import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LocalRuntimeConfigLoaderSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with OptionValues:

  private def load(values: (String, String)*): Either[String, LocalRuntimeConfig] =
    val env = values.toMap
    LocalRuntimeConfigLoader.loadFrom(key => env.get(key))

  "LocalRuntimeConfigLoader" should "default to in-memory persistence" in {
    val config = load().value

    config.persistence shouldBe LocalPersistenceMode.InMemory
    config.sqlite shouldBe None
  }

  it should "parse sqlite persistence with the default database path" in {
    val config = load("PERSISTENCE_MODE" -> "sqlite").value

    config.persistence shouldBe LocalPersistenceMode.SQLite
    config.sqlite.value.path shouldBe "chess.db"
  }

  it should "parse sqlite persistence with a custom CHESS_DB_PATH" in {
    val config =
      load("PERSISTENCE_MODE" -> "sqlite", "CHESS_DB_PATH" -> "/data/local-game.db").value

    config.sqlite.value.path shouldBe "/data/local-game.db"
  }

  it should "reject unknown persistence modes" in {
    load("PERSISTENCE_MODE" -> "postgres").left.value should include("PERSISTENCE_MODE")
  }
