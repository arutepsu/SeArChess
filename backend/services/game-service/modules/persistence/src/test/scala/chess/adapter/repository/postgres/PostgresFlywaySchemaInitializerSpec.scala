package chess.adapter.repository.postgres

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.io.Source

class PostgresFlywaySchemaInitializerSpec extends AnyFlatSpec with Matchers:

  "Postgres Flyway migrations" should "include the initial session persistence schema" in {
    val resourcePath =
      PostgresFlywaySchemaInitializer.MigrationResourcePath.stripPrefix("classpath:") +
        "/V1__create_session_persistence.sql"

    val resource =
      Option(getClass.getClassLoader.getResource(resourcePath))
        .getOrElse(fail(s"Missing Flyway migration resource: $resourcePath"))

    val sql = Source.fromURL(resource).mkString.toLowerCase

    sql should include("create table if not exists sessions")
    sql should include("create table if not exists game_states")
    sql should include("constraint sessions_game_id_key unique (game_id)")
    sql should include("create index if not exists sessions_lifecycle_idx")
  }
