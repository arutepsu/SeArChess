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
