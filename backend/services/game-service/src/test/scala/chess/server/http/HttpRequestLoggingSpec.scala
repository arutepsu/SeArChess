package chess.server.http

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.{Headers, Method, Request, Response, Status, Uri}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

class HttpRequestLoggingSpec extends AnyFlatSpec with Matchers:

  // ─── extractPerfHeader ────────────────────────────────────────────────────

  "HttpRequestLoggingMiddleware.extractPerfHeader" should "return the header value when present" in:
    val headers = Headers(
      org.http4s.Header.Raw(CIString("X-Performance-Run-Id"), "run-abc-123")
    )
    HttpRequestLoggingMiddleware.extractPerfHeader(headers, "X-Performance-Run-Id") shouldBe "run-abc-123"

  it should "return 'none' when the header is absent" in:
    HttpRequestLoggingMiddleware.extractPerfHeader(Headers.empty, "X-Performance-Run-Id") shouldBe "none"

  it should "match the header name case-insensitively" in:
    val headers = Headers(
      org.http4s.Header.Raw(CIString("x-performance-tool"), "k6")
    )
    HttpRequestLoggingMiddleware.extractPerfHeader(headers, "X-Performance-Tool") shouldBe "k6"

  it should "return 'none' for each missing performance header" in:
    val headers = Headers.empty
    HttpRequestLoggingMiddleware.extractPerfHeader(headers, "X-Performance-Tool")     shouldBe "none"
    HttpRequestLoggingMiddleware.extractPerfHeader(headers, "X-Performance-Workload") shouldBe "none"
    HttpRequestLoggingMiddleware.extractPerfHeader(headers, "X-Performance-Phase")    shouldBe "none"

  // ─── successful response passthrough ─────────────────────────────────────

  "HttpRequestLoggingMiddleware" should "pass the response through unchanged" in:
    val inner      = org.http4s.HttpApp[IO] { _ => IO.pure(Response[IO](Status.Ok)) }
    val middleware = HttpRequestLoggingMiddleware(inner)
    val request    = Request[IO](Method.GET, Uri.unsafeFromString("/sessions"))
    middleware.run(request).unsafeRunSync().status shouldBe Status.Ok

  it should "pass a 201 Created response through unchanged" in:
    val inner      = org.http4s.HttpApp[IO] { _ => IO.pure(Response[IO](Status.Created)) }
    val middleware = HttpRequestLoggingMiddleware(inner)
    val request    = Request[IO](Method.POST, Uri.unsafeFromString("/sessions"))
    middleware.run(request).unsafeRunSync().status shouldBe Status.Created

  it should "pass a 404 Not Found response through unchanged" in:
    val inner      = org.http4s.HttpApp[IO] { _ => IO.pure(Response[IO](Status.NotFound)) }
    val middleware = HttpRequestLoggingMiddleware(inner)
    val request    = Request[IO](Method.GET, Uri.unsafeFromString("/games/no-such-id"))
    middleware.run(request).unsafeRunSync().status shouldBe Status.NotFound

  it should "pass through a request that carries all four performance headers" in:
    val inner      = org.http4s.HttpApp[IO] { _ => IO.pure(Response[IO](Status.Ok)) }
    val middleware = HttpRequestLoggingMiddleware(inner)
    val request = Request[IO](
      Method.GET,
      Uri.unsafeFromString("/health"),
      headers = Headers(
        org.http4s.Header.Raw(CIString("X-Performance-Run-Id"),    "run-001"),
        org.http4s.Header.Raw(CIString("X-Performance-Tool"),      "gatling"),
        org.http4s.Header.Raw(CIString("X-Performance-Workload"),  "load"),
        org.http4s.Header.Raw(CIString("X-Performance-Phase"),     "baseline")
      )
    )
    middleware.run(request).unsafeRunSync().status shouldBe Status.Ok

  // ─── error path: re-raise ────────────────────────────────────────────────

  it should "re-raise the original error when the inner app fails" in:
    val boom       = new RuntimeException("boom")
    val inner      = org.http4s.HttpApp[IO] { _ => IO.raiseError(boom) }
    val middleware = HttpRequestLoggingMiddleware(inner)
    val request    = Request[IO](Method.GET, Uri.unsafeFromString("/sessions"))
    val caught = intercept[RuntimeException] {
      middleware.run(request).unsafeRunSync()
    }
    caught shouldBe boom

  it should "re-raise the exact error type when the inner app fails" in:
    val inner      = org.http4s.HttpApp[IO] { _ => IO.raiseError(new IllegalStateException("bad state")) }
    val middleware = HttpRequestLoggingMiddleware(inner)
    val request    = Request[IO](Method.GET, Uri.unsafeFromString("/sessions"))
    intercept[IllegalStateException] {
      middleware.run(request).unsafeRunSync()
    }

  it should "re-raise the error even when performance headers are present" in:
    val boom  = new RuntimeException("upstream failure")
    val inner = org.http4s.HttpApp[IO] { _ => IO.raiseError(boom) }
    val middleware = HttpRequestLoggingMiddleware(inner)
    val request = Request[IO](
      Method.POST,
      Uri.unsafeFromString("/games/game-123/moves"),
      headers = Headers(
        org.http4s.Header.Raw(CIString("X-Performance-Run-Id"),    "run-002"),
        org.http4s.Header.Raw(CIString("X-Performance-Tool"),      "k6"),
        org.http4s.Header.Raw(CIString("X-Performance-Workload"),  "stress"),
        org.http4s.Header.Raw(CIString("X-Performance-Phase"),     "optimized")
      )
    )
    val caught = intercept[RuntimeException] {
      middleware.run(request).unsafeRunSync()
    }
    caught shouldBe boom

  it should "not swallow errors with no message" in:
    val inner      = org.http4s.HttpApp[IO] { _ => IO.raiseError(new IllegalArgumentException()) }
    val middleware = HttpRequestLoggingMiddleware(inner)
    val request    = Request[IO](Method.GET, Uri.unsafeFromString("/health"))
    intercept[IllegalArgumentException] {
      middleware.run(request).unsafeRunSync()
    }
