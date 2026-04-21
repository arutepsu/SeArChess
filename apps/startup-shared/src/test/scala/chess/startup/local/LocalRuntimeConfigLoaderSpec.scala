package chess.startup.local

import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

<<<<<<< HEAD
class LocalRuntimeConfigLoaderSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with OptionValues:
=======
class LocalRuntimeConfigLoaderSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)

  private def load(values: (String, String)*): Either[String, LocalRuntimeConfig] =
    val env = values.toMap
    LocalRuntimeConfigLoader.loadFrom(key => env.get(key))

  "LocalRuntimeConfigLoader" should "default to in-memory persistence" in {
    val config = load().value

    config.persistence shouldBe LocalPersistenceMode.InMemory
<<<<<<< HEAD
    config.sqlite shouldBe None
=======
    config.sqlite      shouldBe None
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
  }

  it should "parse sqlite persistence with the default database path" in {
    val config = load("PERSISTENCE_MODE" -> "sqlite").value

<<<<<<< HEAD
    config.persistence shouldBe LocalPersistenceMode.SQLite
=======
    config.persistence       shouldBe LocalPersistenceMode.SQLite
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
    config.sqlite.value.path shouldBe "chess.db"
  }

  it should "parse sqlite persistence with a custom CHESS_DB_PATH" in {
<<<<<<< HEAD
    val config =
      load("PERSISTENCE_MODE" -> "sqlite", "CHESS_DB_PATH" -> "/data/local-game.db").value
=======
    val config = load("PERSISTENCE_MODE" -> "sqlite", "CHESS_DB_PATH" -> "/data/local-game.db").value
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)

    config.sqlite.value.path shouldBe "/data/local-game.db"
  }

  it should "reject unknown persistence modes" in {
<<<<<<< HEAD
    load("PERSISTENCE_MODE" -> "postgres").left.value should include("PERSISTENCE_MODE")
=======
    load("PERSISTENCE_MODE" -> "postgres").left.value should include ("PERSISTENCE_MODE")
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
  }
