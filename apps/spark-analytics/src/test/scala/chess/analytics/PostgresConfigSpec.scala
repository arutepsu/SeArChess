package chess.analytics

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PostgresConfigSpec extends AnyFlatSpec with Matchers {

  private val fullEnv = Map(
    "POSTGRES_WRITE_ENABLED" -> "true",
    "POSTGRES_URL"           -> "jdbc:postgresql://localhost:5432/searchess",
    "POSTGRES_USER"          -> "searchess",
    "POSTGRES_PASSWORD"      -> "searchess"
  )

  // ── write-enabled gate ────────────────────────────────────────────────────

  "PostgresConfig.fromEnvMap" should "return None when POSTGRES_WRITE_ENABLED is absent" in {
    PostgresConfig.fromEnvMap(Map.empty) shouldBe None
  }

  it should "return None when POSTGRES_WRITE_ENABLED is 'false'" in {
    PostgresConfig.fromEnvMap(Map("POSTGRES_WRITE_ENABLED" -> "false")) shouldBe None
  }

  it should "accept POSTGRES_WRITE_ENABLED case-insensitively" in {
    PostgresConfig.fromEnvMap(fullEnv + ("POSTGRES_WRITE_ENABLED" -> "TRUE")) should not be empty
  }

  // ── required vars ─────────────────────────────────────────────────────────

  it should "return None when enabled but POSTGRES_URL is missing" in {
    PostgresConfig.fromEnvMap(fullEnv - "POSTGRES_URL") shouldBe None
  }

  it should "return None when enabled but POSTGRES_USER is missing" in {
    PostgresConfig.fromEnvMap(fullEnv - "POSTGRES_USER") shouldBe None
  }

  it should "return None when enabled but POSTGRES_PASSWORD is missing" in {
    PostgresConfig.fromEnvMap(fullEnv - "POSTGRES_PASSWORD") shouldBe None
  }

  it should "return Some(config) with defaults when all required vars are present" in {
    PostgresConfig.fromEnvMap(fullEnv) shouldBe Some(PostgresConfig(
      url         = "jdbc:postgresql://localhost:5432/searchess",
      user        = "searchess",
      password    = "searchess",
      schema      = "public",
      writeMode   = "overwrite",
      strictWrite = false
    ))
  }

  // ── schema ────────────────────────────────────────────────────────────────

  it should "default schema to 'public' when POSTGRES_SCHEMA is absent" in {
    PostgresConfig.fromEnvMap(fullEnv).map(_.schema) shouldBe Some("public")
  }

  it should "use POSTGRES_SCHEMA when provided" in {
    PostgresConfig.fromEnvMap(fullEnv + ("POSTGRES_SCHEMA" -> "analytics")).map(_.schema) shouldBe Some("analytics")
  }

  // ── write mode ────────────────────────────────────────────────────────────

  it should "default writeMode to 'overwrite' when POSTGRES_WRITE_MODE is absent" in {
    PostgresConfig.fromEnvMap(fullEnv).map(_.writeMode) shouldBe Some("overwrite")
  }

  it should "use POSTGRES_WRITE_MODE=append when provided" in {
    PostgresConfig.fromEnvMap(fullEnv + ("POSTGRES_WRITE_MODE" -> "append")).map(_.writeMode) shouldBe Some("append")
  }

  // ── strict write ──────────────────────────────────────────────────────────

  it should "default strictWrite to false when POSTGRES_STRICT_WRITE is absent" in {
    PostgresConfig.fromEnvMap(fullEnv).map(_.strictWrite) shouldBe Some(false)
  }

  it should "set strictWrite=true when POSTGRES_STRICT_WRITE=true" in {
    PostgresConfig.fromEnvMap(fullEnv + ("POSTGRES_STRICT_WRITE" -> "true")).map(_.strictWrite) shouldBe Some(true)
  }

  it should "accept POSTGRES_STRICT_WRITE case-insensitively" in {
    PostgresConfig.fromEnvMap(fullEnv + ("POSTGRES_STRICT_WRITE" -> "TRUE")).map(_.strictWrite) shouldBe Some(true)
  }

  // ── PostgresWriter: qualified table name ──────────────────────────────────

  "PostgresWriter.qualifiedTableName" should "prefix table name with default public schema" in {
    val cfg    = PostgresConfig("jdbc:postgresql://localhost/db", "u", "p", "public")
    val writer = new PostgresWriter(cfg, runId = "r1", sourcePath = "/data/events.jsonl")
    writer.qualifiedTableName("analytics_leaderboard") shouldBe "public.analytics_leaderboard"
  }

  it should "use custom schema in qualified table name" in {
    val cfg    = PostgresConfig("jdbc:postgresql://localhost/db", "u", "p", "arena")
    val writer = new PostgresWriter(cfg, runId = "r1", sourcePath = "/data/events.jsonl")
    writer.qualifiedTableName("analytics_leaderboard") shouldBe "arena.analytics_leaderboard"
  }
}
