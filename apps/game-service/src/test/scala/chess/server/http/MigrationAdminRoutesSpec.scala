package chess.server.http

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.application.migration.*
import chess.server.migration.MigrationCommand
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

import java.time.Instant

/** Unit tests for MigrationAdminRoutes.
  *
  * All tests inject a stub migration runner so no real database connections are required. The stub
  * approach covers validation, error-code, and JSON-report contract without Docker or Testcontainers.
  */
class MigrationAdminRoutesSpec extends AnyFlatSpec with Matchers:

  private val stubReport: MigrationReport = MigrationReport.fromItems(
    mode = MigrationMode.DryRun,
    sourceAdapterName = "postgres",
    targetAdapterName = "mongo",
    startedAt = Instant.parse("2024-01-01T00:00:00Z"),
    finishedAt = Instant.parse("2024-01-01T00:00:01Z"),
    batchSize = 100,
    batchCount = 0,
    itemResults = List.empty,
    fatalFailure = None,
    runId = MigrationRunId("test-run-123")
  )

  private val stubOk: MigrationCommand => Either[String, MigrationReport]    = _ => Right(stubReport)
  private val stubFail: MigrationCommand => Either[String, MigrationReport]  = _ => Left("DB connection failed")

  private val testToken             = "test-secret-token"
  private val AdminTokenHeaderName  = CIString("X-Admin-Token")

  private def routes(stub: MigrationCommand => Either[String, MigrationReport] = stubOk) =
    MigrationAdminRoutes(testToken, stub).routes.orNotFound

  private def post(body: String): Request[IO] =
    Request[IO](
      method = Method.POST,
      uri = Uri.unsafeFromString("/admin/migrations"),
      headers = Headers(Header.Raw(AdminTokenHeaderName, testToken)),
      body = Stream.emits(body.getBytes("UTF-8")).covary[IO]
    )

  private def postWithoutToken(body: String): Request[IO] =
    Request[IO](
      method = Method.POST,
      uri = Uri.unsafeFromString("/admin/migrations"),
      body = Stream.emits(body.getBytes("UTF-8")).covary[IO]
    )

  private def postWithToken(body: String, token: String): Request[IO] =
    Request[IO](
      method = Method.POST,
      uri = Uri.unsafeFromString("/admin/migrations"),
      headers = Headers(Header.Raw(AdminTokenHeaderName, token)),
      body = Stream.emits(body.getBytes("UTF-8")).covary[IO]
    )

  private def run(req: Request[IO], stub: MigrationCommand => Either[String, MigrationReport] = stubOk): Response[IO] =
    routes(stub).run(req).unsafeRunSync()

  private def bodyJson(resp: Response[IO]): ujson.Value =
    ujson.read(resp.bodyText.compile.string.unsafeRunSync())

  // ── Happy path ────────────────────────────────────────────────────────────

  "MigrationAdminRoutes" should "return 200 with a migration report for a dry-run request" in {
    val resp = run(post("""{"source":"postgres","target":"mongo","mode":"dry-run"}"""))

    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("runId").str shouldBe "test-run-123"
    json("mode").str shouldBe "DryRun"
    json("source").str shouldBe "postgres"
    json("target").str shouldBe "mongo"
    json("status").str shouldBe "Success"
  }

  it should "return 200 with a migration report for execute mode with correct confirmation" in {
    val resp = run(
      post("""{"source":"postgres","target":"mongo","mode":"execute","confirmation":"MIGRATE"}""")
    )

    resp.status shouldBe Status.Ok
    bodyJson(resp)("runId").str shouldBe "test-run-123"
  }

  it should "return 200 with a migration report for validate-only mode" in {
    val resp = run(post("""{"source":"mongo","target":"postgres","mode":"validate-only"}"""))

    resp.status shouldBe Status.Ok
    bodyJson(resp)("runId").str shouldBe "test-run-123"
  }

  it should "pass optional batchSize and validateAfterExecute through to the migration command" in {
    var capturedCommand: Option[MigrationCommand] = None
    val capturingStub: MigrationCommand => Either[String, MigrationReport] = cmd =>
      capturedCommand = Some(cmd)
      Right(stubReport)

    run(
      post(
        """{"source":"postgres","target":"mongo","mode":"execute","batchSize":50,"validateAfterExecute":true,"confirmation":"MIGRATE"}"""
      ),
      capturingStub
    )

    capturedCommand.map(_.batchSize) shouldBe Some(50)
    capturedCommand.map(_.validateAfterExecute) shouldBe Some(true)
  }

  // ── Validation errors ─────────────────────────────────────────────────────

  it should "return 400 when source is missing" in {
    val resp = run(post("""{"target":"mongo","mode":"dry-run"}"""))

    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
    bodyJson(resp)("message").str should include("source")
  }

  it should "return 400 when target is missing" in {
    val resp = run(post("""{"source":"postgres","mode":"dry-run"}"""))

    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
    bodyJson(resp)("message").str should include("target")
  }

  it should "return 400 when mode is missing" in {
    val resp = run(post("""{"source":"postgres","target":"mongo"}"""))

    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
    bodyJson(resp)("message").str should include("mode")
  }

  it should "return 400 when source and target are the same backend" in {
    val resp = run(post("""{"source":"postgres","target":"postgres","mode":"dry-run"}"""))

    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("message").str should include("source and target must differ")
  }

  it should "return 400 for an unsupported source backend" in {
    val resp = run(post("""{"source":"mysql","target":"mongo","mode":"dry-run"}"""))

    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("message").str should include("mysql")
  }

  it should "return 400 for an unsupported target backend" in {
    val resp = run(post("""{"source":"postgres","target":"oracle","mode":"dry-run"}"""))

    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("message").str should include("oracle")
  }

  it should "return 400 for an unsupported mode" in {
    val resp = run(post("""{"source":"postgres","target":"mongo","mode":"copy"}"""))

    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("message").str should include("Unsupported mode")
  }

  it should "return 400 when validateAfterExecute is true with dry-run mode" in {
    val resp = run(
      post("""{"source":"postgres","target":"mongo","mode":"dry-run","validateAfterExecute":true}""")
    )

    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("message").str should include("validateAfterExecute")
  }

  it should "return 400 when validateAfterExecute is true with validate-only mode" in {
    val resp = run(
      post(
        """{"source":"postgres","target":"mongo","mode":"validate-only","validateAfterExecute":true}"""
      )
    )

    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("message").str should include("validateAfterExecute")
  }

  it should "return 400 when execute mode has no confirmation" in {
    val resp = run(post("""{"source":"postgres","target":"mongo","mode":"execute"}"""))

    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("message").str should include("MIGRATE")
  }

  it should "return 400 when execute mode has wrong confirmation" in {
    val resp = run(
      post("""{"source":"postgres","target":"mongo","mode":"execute","confirmation":"yes"}""")
    )

    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("message").str should include("MIGRATE")
  }

  it should "return 400 for invalid JSON body" in {
    val resp = run(post("not json"))

    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  it should "return 400 when body is a JSON array instead of an object" in {
    val resp = run(post("""["source","target"]"""))

    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  it should "return 400 for a non-positive batchSize" in {
    val resp = run(post("""{"source":"postgres","target":"mongo","mode":"dry-run","batchSize":0}"""))

    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("message").str should include("batchSize")
  }

  // ── Runtime failure ───────────────────────────────────────────────────────

  it should "return 500 when the migration runner returns an error" in {
    val resp = run(post("""{"source":"postgres","target":"mongo","mode":"dry-run"}"""), stubFail)

    resp.status shouldBe Status.InternalServerError
    bodyJson(resp)("code").str shouldBe "MIGRATION_FAILED"
    bodyJson(resp)("message").str should include("DB connection failed")
  }

  // ── JSON report fields ────────────────────────────────────────────────────

  it should "include all expected top-level fields in the migration report JSON" in {
    val resp = run(post("""{"source":"postgres","target":"mongo","mode":"dry-run"}"""))
    val json = bodyJson(resp)

    json.obj.keys should contain allOf (
      "runId", "mode", "source", "target", "status",
      "scanned", "migrated", "wouldMigrate", "conflicts", "failed",
      "validationRan", "validationResult", "batchSize", "batchCount",
      "startedAt", "finishedAt", "duration", "itemResultsPreview"
    )
  }

  // ── Token authentication ──────────────────────────────────────────────────

  it should "return 401 when X-Admin-Token header is absent" in {
    val resp = run(postWithoutToken("""{"source":"postgres","target":"mongo","mode":"dry-run"}"""))

    resp.status shouldBe Status.Unauthorized
    bodyJson(resp)("code").str shouldBe "UNAUTHORIZED"
    bodyJson(resp)("message").str should include("X-Admin-Token")
  }

  it should "return 401 when X-Admin-Token is present but incorrect" in {
    val resp = run(postWithToken("""{"source":"postgres","target":"mongo","mode":"dry-run"}""", "wrong-token"))

    resp.status shouldBe Status.Unauthorized
    bodyJson(resp)("code").str shouldBe "UNAUTHORIZED"
    bodyJson(resp)("message").str should include("invalid")
  }

  it should "return 200 and reach migration logic when X-Admin-Token is correct" in {
    var reached = false
    val capturingStub: MigrationCommand => Either[String, MigrationReport] = cmd =>
      reached = true
      Right(stubReport)

    val resp = routes(capturingStub).run(post("""{"source":"postgres","target":"mongo","mode":"dry-run"}""")).unsafeRunSync()

    resp.status shouldBe Status.Ok
    reached shouldBe true
  }
