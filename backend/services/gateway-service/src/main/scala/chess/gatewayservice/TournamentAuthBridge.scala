package chess.gatewayservice

import cats.effect.IO
import fs2.Stream
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`

/** Owns the server-side auth bridge to the tournament-server.
  *
  * User tokens (isBot=false): keyed by Keycloak sub in `cache`.
  * Bot tokens (isBot=true): keyed by tournament-server botId in `botJwtCache`.
  *
  * On the first call for a subject/botId, this class calls POST /api/auth/register
  * on the tournament-server, caches the returned JWT, and reuses it for subsequent
  * calls. On 401 the cache entry is evicted and registration is retried once.
  *
  * For bot tokens, ownership is verified by checking that the tournament-server's
  * registration for (botName, isBot=true) returns the expected botId. A mismatch
  * means the caller provided a wrong botName for the claimed botId and is rejected.
  *
  * Neither user nor bot JWTs are ever forwarded to the browser.
  */
final class TournamentAuthBridge(
  client: Client[IO],
  tournamentServerUrl: String,
  cache: TournamentJwtCache,
  botJwtCache: TournamentJwtCache
):

  private val upstreamBase = Uri.unsafeFromString(tournamentServerUrl)

  /** Execute `mkReq(token)` using a cached or freshly-registered user token.
    * On 401, clears cache and retries once.
    */
  def withToken(sub: String, displayName: String)(
    mkReq: String => Request[IO]
  ): IO[Response[IO]] =
    getOrAcquireToken(sub, displayName).flatMap {
      case Left(err)    => IO.pure(authFailure(err))
      case Right(entry) => executeWithRetry(sub, displayName, entry.token, mkReq)
    }

  /** Execute `mkReq(botToken)` using a cached or freshly-registered bot JWT
    * (isBot=true). Ownership is verified by confirming that the tournament-server's
    * idempotent registration for (botName, isBot=true) returns `botId`. A mismatch
    * is rejected with BOT_ID_MISMATCH before any upstream call is made.
    * On 401, evicts the bot JWT cache and retries once.
    */
  def withBotToken(botId: String, botName: String)(
    mkReq: String => Request[IO]
  ): IO[Response[IO]] =
    getOrAcquireBotToken(botId, botName).flatMap {
      case Left("bot_id_mismatch") =>
        IO.pure(buildJsonResponse(
          Status.BadRequest,
          ujson.write(ujson.Obj(
            "code"    -> "BOT_ID_MISMATCH",
            "message" -> s"botName '$botName' does not match the registered identity for botId '$botId'"
          )).getBytes("UTF-8")
        ))
      case Left(err)    => IO.pure(authFailure(err))
      case Right(entry) => executeBotWithRetry(botId, botName, entry.token, mkReq)
    }

  private def getOrAcquireBotToken(
    botId: String,
    botName: String
  ): IO[Either[String, TournamentJwtCache.Entry]] =
    botJwtCache.get(botId) match
      case Some(entry) => IO.pure(Right(entry))
      case None        => registerBot(botId, botName)

  private def registerBot(
    expectedBotId: String,
    botName: String
  ): IO[Either[String, TournamentJwtCache.Entry]] =
    val uri  = upstreamBase.withPath(Uri.Path.unsafeFromString("/api/auth/register"))
    val body = ujson.write(ujson.Obj("name" -> botName, "isBot" -> true))
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
            val json       = ujson.read(respBody)
            val returnedId = json("id").str
            if returnedId != expectedBotId then
              Left("bot_id_mismatch")
            else
              val entry = TournamentJwtCache.Entry(returnedId, json("token").str)
              botJwtCache.put(expectedBotId, entry)
              Right(entry)
          catch
            case _: Exception => Left("Bot registration response parse failure")
        else
          Left(s"Bot registration failed: ${resp.status.code}")
      }
    }.handleErrorWith { e =>
      IO.pure(Left(s"Tournament-server unreachable during bot registration: ${Option(e.getMessage).getOrElse("unknown")}"))
    }

  private def executeBotWithRetry(
    botId: String,
    botName: String,
    token: String,
    mkReq: String => Request[IO]
  ): IO[Response[IO]] =
    runRequest(mkReq(token)).flatMap { (status, body) =>
      if status == Status.Unauthorized then
        botJwtCache.remove(botId)
        registerBot(botId, botName).flatMap {
          case Left("bot_id_mismatch") =>
            IO.pure(buildJsonResponse(
              Status.BadRequest,
              ujson.write(ujson.Obj(
                "code"    -> "BOT_ID_MISMATCH",
                "message" -> s"botName '$botName' does not match the registered identity for botId '$botId'"
              )).getBytes("UTF-8")
            ))
          case Left(err)    => IO.pure(authFailure(err))
          case Right(entry) => runRequest(mkReq(entry.token)).map((s, b) => buildJsonResponse(s, b))
        }
      else
        IO.pure(buildJsonResponse(status, body))
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
