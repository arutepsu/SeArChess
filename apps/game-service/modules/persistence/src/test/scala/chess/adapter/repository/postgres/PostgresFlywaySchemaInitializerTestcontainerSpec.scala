package chess.adapter.repository.postgres

import chess.adapter.repository.testcontainers.PostgresTestcontainerFixture
import org.scalatest.Assertions.cancel
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Outcome
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class PostgresFlywaySchemaInitializerTestcontainerSpec
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterAll:

  private val postgres = PostgresTestcontainerFixture()

  override protected def withFixture(test: NoArgTest): Outcome =
    if !postgres.isDockerAvailable then
      cancel("Docker/Testcontainers unavailable; skipping PostgreSQL container integration tests")
    postgres.start()
    // Drop managed public tables before each test so state from a prior test cannot
    // contaminate subsequent ones (e.g. flyway_schema_history left by a previous run).
    postgres.withDatabase { db =>
      Await.result(
        db.run(
          // Drop external_game_bindings first (it FK-references sessions and game_states).
          sqlu"drop table if exists flyway_schema_history, external_game_bindings, game_states, sessions, legacy_public_table cascade"
        ),
        10.seconds
      )
    }
    super.withFixture(test)

  override protected def afterAll(): Unit =
    postgres.stop()
    super.afterAll()

  "PostgresFlywaySchemaInitializer" should "initialize an empty Testcontainers Postgres database" in {
    postgres.resetWithFlyway()

    postgres.withDatabase { db =>
      val tables =
        Await.result(
          db.run(
            sql"""
              select table_name
              from information_schema.tables
              where table_schema = 'public'
              order by table_name
            """.as[String]
          ),
          10.seconds
        ).toSet

      tables should contain("flyway_schema_history")
      tables should contain("sessions")
      tables should contain("game_states")
      tables should contain("external_game_bindings")
    }
  }

  it should "initialize the configured schema when public is non-empty" in {
    postgres.withDatabase { db =>
      Await.result(
        db.run(
          sqlu"""
            create table if not exists public.legacy_public_table (
              id integer primary key
            )
          """
        ),
        10.seconds
      )
    }

    postgres.resetWithFlyway(Some("game"))

    postgres.withDatabase { db =>
      val gameTables =
        Await.result(
          db.run(
            sql"""
              select table_name
              from information_schema.tables
              where table_schema = 'game'
              order by table_name
            """.as[String]
          ),
          10.seconds
        ).toSet

      val publicTables =
        Await.result(
          db.run(
            sql"""
              select table_name
              from information_schema.tables
              where table_schema = 'public'
              order by table_name
            """.as[String]
          ),
          10.seconds
        ).toSet

      gameTables should contain("flyway_schema_history")
      gameTables should contain("sessions")
      gameTables should contain("game_states")
      gameTables should contain("external_game_bindings")
      publicTables should contain("legacy_public_table")
      publicTables should not contain "flyway_schema_history"
    }
  }
