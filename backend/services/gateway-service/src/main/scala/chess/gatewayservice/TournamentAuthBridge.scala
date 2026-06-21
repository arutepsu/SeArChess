package chess.gatewayservice

import cats.effect.IO
import fs2.Stream
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`

/** Owns the server-side auth bridge to the tournament-server.
  *
  * On the first call for a Keycloak subject, it calls POST /api/auth/register on
  * the tournament-server (with isBot=false), caches the returned (userId, JWT),
  * and uses that JWT for all subsequent protected requests.
  *
  * If a protected call gets a 401, the cache entry is cleared and registration is
  * retried once. Tournament-server JWTs are never forwarded to the browser.
  */
final class TournamentAuthBridge(
  client: Client[IO],
  tournamentServerUrl: String,
  cache: TournamentJwtCache
):

  private val upstreamBase = Uri.unsafeFromString(tournamentServerUrl)

  /** Execute `mkReq(token)` using a cached or freshly-registered token.
    * On 401, clears cache and retries once.
    */
  def withToken(sub: String, displayName: String)(
    mkReq: String => Request[IO]
  ): IO[Response[IO]] =
    getOrAcquireToken(sub, displayName).flatMap {
      case Left(err)    => IO.pure(authFailure(err))
      case Right(entry) => executeWithRetry(sub, displayName, entry.token, mkReq)
    }

  private def executeWithRetry(
    sub: String,
    displayName: String,
    token: String,
    mkReq: String => Request[IO]
  ): IO[Response[IO]] =
    runRequest(mkReq(token)).flatMap { (status, body) =>
      if status == Status.Unauthorized then
        cache.remove(sub)
        register(sub, displayName).flatMap {
          case Left(err)    => IO.pure(authFailure(err))
          case Right(entry) => runRequest(mkReq(entry.token)).map((s, b) => buildJsonResponse(s, b))
        }
      else
        IO.pure(buildJsonResponse(status, body))
    }

  private def getOrAcquireToken(
    sub: String,
    displayName: String
  ): IO[Either[String, TournamentJwtCache.Entry]] =
    cache.get(sub) match
      case Some(entry) => IO.pure(Right(entry))
      case None        => register(sub, displayName)

  private def register(
    sub: String,
    displayName: String
  ): IO[Either[String, TournamentJwtCache.Entry]] =
    val uri  = upstreamBase.withPath(Uri.Path.unsafeFromString("/api/auth/register"))
    val body = ujson.write(ujson.Obj("name" -> displayName, "isBot" -> false))
    val req  = Request[IO](
      method  = Method.POST,
      uri     = uri,
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body    = Stream.emits(body.getBytes("UTF-8")).covary[IO]
    )
    client.run(req).use { resp =>
      resp.as[String].map { respBody =>
        if resp.status.isSuccess then
          try
            val json  = ujson.read(respBody)
            val entry = TournamentJwtCache.Entry(json("id").str, json("token").str)
            cache.put(sub, entry)
            Right(entry)
          catch
            case _: Exception => Left("Tournament-server registration response parse failure")
        else
          Left(s"Tournament-server registration failed: ${resp.status.code}")
      }
    }.handleErrorWith { e =>
      IO.pure(Left(s"Tournament-server unreachable during registration: ${Option(e.getMessage).getOrElse("unknown")}"))
    }

  /** Like [[withToken]] but returns a streaming response without buffering the body.
    *
    * Uses Resource.allocated so the upstream HTTP connection stays open while the response
    * body stream is consumed by the downstream client. The connection is released via
    * onFinalize when the stream ends or is cancelled.
    *
    * On 401, closes the first connection, evicts the cache, re-registers once, and retries.
    * Tournament-server JWTs are never forwarded to the browser.
    */
  def withTokenStreaming(sub: String, displayName: String)(
    mkReq: String => Request[IO]
  ): IO[Response[IO]] =
    getOrAcquireToken(sub, displayName).flatMap {
      case Left(err)    => IO.pure(authFailure(err))
      case Right(entry) => streamWithRetry(sub, displayName, entry.token, mkReq)
    }.handleErrorWith { _ =>
      IO.pure(buildJsonResponse(Status.ServiceUnavailable, unavailableBody))
    }

  private def streamWithRetry(
    sub: String,
    displayName: String,
    token: String,
    mkReq: String => Request[IO]
  ): IO[Response[IO]] =
    client.run(mkReq(token)).allocated.flatMap { case (upstreamResp, releaseConn) =>
      if upstreamResp.status == Status.Unauthorized then
        releaseConn
          .flatMap { _ => IO(cache.remove(sub)) }
          .flatMap { _ => register(sub, displayName) }
          .flatMap {
            case Left(err)    => IO.pure(authFailure(err))
            case Right(entry) =>
              client.run(mkReq(entry.token)).allocated.map { case (r2, rel2) =>
                buildNdjsonStreamResponse(r2.status, r2.body.onFinalize(rel2))
              }
          }
      else
        IO.pure(buildNdjsonStreamResponse(upstreamResp.status, upstreamResp.body.onFinalize(releaseConn)))
    }.handleErrorWith { _ =>
      IO.pure(buildJsonResponse(Status.ServiceUnavailable, unavailableBody))
    }

  private def buildNdjsonStreamResponse(status: Status, body: Stream[IO, Byte]): Response[IO] =
    Response[IO](
      status  = status,
      headers = Headers(
        Header.Raw(org.typelevel.ci.CIString("Content-Type"), "application/x-ndjson"),
        Header.Raw(org.typelevel.ci.CIString("Cache-Control"), "no-cache"),
        Header.Raw(org.typelevel.ci.CIString("X-Accel-Buffering"), "no")
      ),
      body = body
    )

  private def runRequest(req: Request[IO]): IO[(Status, Array[Byte])] =
    client.run(req).use { resp =>
      resp.as[String].map(b => (resp.status, b.getBytes("UTF-8")))
    }.handleErrorWith { _ =>
      IO.pure((Status.ServiceUnavailable, unavailableBody))
    }

  private def buildJsonResponse(status: Status, body: Array[Byte]): Response[IO] =
    Response[IO](
      status  = status,
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body    = Stream.emits(body).covary[IO]
    )

  private def authFailure(msg: String): Response[IO] =
    val body = s"""{"code":"TOURNAMENT_AUTH_FAILED","message":${ujson.write(msg)}}"""
    buildJsonResponse(Status.ServiceUnavailable, body.getBytes("UTF-8"))

  private val unavailableBody: Array[Byte] =
    """{"code":"TOURNAMENT_SERVER_UNAVAILABLE","message":"Tournament server is currently unavailable"}"""
      .getBytes("UTF-8")
