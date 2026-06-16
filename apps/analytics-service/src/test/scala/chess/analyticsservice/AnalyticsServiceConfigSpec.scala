package chess.analyticsservice

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AnalyticsServiceConfigSpec extends AnyFlatSpec with Matchers:

  private val minimalEnv: Map[String, String] = Map(
    "ANALYTICS_POSTGRES_URL" -> "jdbc:postgresql://localhost:5432/searchess"
  )

  private def load(env: Map[String, String]): Either[String, AnalyticsServiceConfig] =
    AnalyticsServiceConfig.load(key => env.get(key).filter(_.nonEmpty))

  // ── Required fields ───────────────────────────────────────────────────────

  "load" should "return Left when ANALYTICS_POSTGRES_URL is missing" in {
    load(Map.empty).isLeft shouldBe true
    load(Map.empty).left.get should include("ANALYTICS_POSTGRES_URL")
  }

  it should "return Right with defaults when only required env vars are set" in {
    val result = load(minimalEnv)
    result shouldBe a[Right[?, ?]]
    val cfg = result.getOrElse(fail())
    cfg.host             shouldBe "0.0.0.0"
    cfg.port             shouldBe 8084
    cfg.postgresUrl      shouldBe "jdbc:postgresql://localhost:5432/searchess"
    cfg.postgresUser     shouldBe "searchess"
    cfg.postgresPassword shouldBe ""
    cfg.postgresSchema   shouldBe "public"
  }

  // ── Port validation ───────────────────────────────────────────────────────

  it should "return Left for non-integer port" in {
    val result = load(minimalEnv + ("ANALYTICS_HTTP_PORT" -> "not-a-number"))
    result.isLeft shouldBe true
    result.left.get should include("ANALYTICS_HTTP_PORT")
  }

  it should "return Left for out-of-range port" in {
    val result = load(minimalEnv + ("ANALYTICS_HTTP_PORT" -> "99999"))
    result.isLeft shouldBe true
    result.left.get should include("ANALYTICS_HTTP_PORT")
  }

  it should "accept a custom valid port" in {
    val result = load(minimalEnv + ("ANALYTICS_HTTP_PORT" -> "9090"))
    result.map(_.port) shouldBe Right(9090)
  }

  // ── Schema validation ─────────────────────────────────────────────────────

  it should "accept 'public' as schema (default)" in {
    load(minimalEnv).map(_.postgresSchema) shouldBe Right("public")
  }

  it should "accept a custom valid schema name" in {
    val result = load(minimalEnv + ("ANALYTICS_POSTGRES_SCHEMA" -> "analytics"))
    result.map(_.postgresSchema) shouldBe Right("analytics")
  }

  it should "accept schema names with underscores and mixed case" in {
    load(minimalEnv + ("ANALYTICS_POSTGRES_SCHEMA" -> "chess_Analytics_2")).map(_.postgresSchema) shouldBe
      Right("chess_Analytics_2")
  }

  it should "accept schema starting with underscore" in {
    load(minimalEnv + ("ANALYTICS_POSTGRES_SCHEMA" -> "_private")).map(_.postgresSchema) shouldBe
      Right("_private")
  }

  it should "return Left for schema starting with a digit" in {
    val result = load(minimalEnv + ("ANALYTICS_POSTGRES_SCHEMA" -> "1bad"))
    result.isLeft shouldBe true
    result.left.get should include("ANALYTICS_POSTGRES_SCHEMA")
    result.left.get should include("[A-Za-z_][A-Za-z0-9_]*")
  }

  it should "return Left for schema with a hyphen" in {
    val result = load(minimalEnv + ("ANALYTICS_POSTGRES_SCHEMA" -> "my-schema"))
    result.isLeft shouldBe true
    result.left.get should include("ANALYTICS_POSTGRES_SCHEMA")
  }

  it should "return Left for schema with a dot" in {
    val result = load(minimalEnv + ("ANALYTICS_POSTGRES_SCHEMA" -> "a.b"))
    result.isLeft shouldBe true
    result.left.get should include("ANALYTICS_POSTGRES_SCHEMA")
  }

  it should "return Left for schema with spaces" in {
    val result = load(minimalEnv + ("ANALYTICS_POSTGRES_SCHEMA" -> "bad schema"))
    result.isLeft shouldBe true
    result.left.get should include("ANALYTICS_POSTGRES_SCHEMA")
  }

  // ── Optional field overrides ───────────────────────────────────────────────

  it should "accept custom host, user, password, and schema together" in {
    val env = minimalEnv ++ Map(
      "ANALYTICS_HTTP_HOST"          -> "127.0.0.1",
      "ANALYTICS_HTTP_PORT"          -> "8099",
      "ANALYTICS_POSTGRES_USER"      -> "admin",
      "ANALYTICS_POSTGRES_PASSWORD"  -> "s3cr3t",
      "ANALYTICS_POSTGRES_SCHEMA"    -> "chess_analytics"
    )
    val result = load(env)
    result shouldBe a[Right[?, ?]]
    val cfg = result.getOrElse(fail())
    cfg.host             shouldBe "127.0.0.1"
    cfg.port             shouldBe 8099
    cfg.postgresUser     shouldBe "admin"
    cfg.postgresPassword shouldBe "s3cr3t"
    cfg.postgresSchema   shouldBe "chess_analytics"
  }
