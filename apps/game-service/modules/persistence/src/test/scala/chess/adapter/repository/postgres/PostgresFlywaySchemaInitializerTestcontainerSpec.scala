package chess.adapter.repository.postgres

import chess.adapter.repository.testcontainers.PostgresTestcontainerFixture
import org.scalatest.BeforeAndAfterAll
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

  override protected def beforeAll(): Unit =
    super.beforeAll()
    postgres.start()

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
      publicTables should contain("legacy_public_table")
      publicTables should not contain "flyway_schema_history"
    }
  }
