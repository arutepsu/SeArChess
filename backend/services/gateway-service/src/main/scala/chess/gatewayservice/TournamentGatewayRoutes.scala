package chess.gatewayservice

import cats.effect.IO
import cats.syntax.semigroupk.*
import fs2.Stream
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

import scala.util.control.NonFatal

import GatewayJwtExtractor.GatewayJwtClaims

class TournamentGatewayRoutes(
  client: Client[IO],
  config: GatewayServiceConfig,
  authBridge: TournamentAuthBridge
):

  private val upstreamBase = Uri.unsafeFromString(config.tournamentServerUrl)

  // ── Liveness ──────────────────────────────────────────────────────────────────

  private val operationalRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "health" =>
      Ok("ok")
  }

  // ── Authenticated streaming + game-snapshot routes ────────────────────────────
  // These proxy long-lived NDJSON responses from the tournament-server without buffering.
  // Keycloak auth required; gateway injects server-side tournament JWT.

  private val streamRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ GET -> Root / "api" / "gateway" / "tournament" / id / "stream" =>
      withDirector(req) { claims =>
        authBridge.withTokenStreaming(claims.sub, claims.preferredUsername) { token =>
          Request[IO](
            method  = Method.GET,
            uri     = upstreamBase.withPath(Uri.Path.unsafeFromString(s"/api/tournament/$id/stream")),
            headers = Headers(Header.Raw(CIString("Authorization"), s"Bearer $token"))
          )
        }
      }

    case req @ GET -> Root / "api" / "gateway" / "tournament" / id / "game" / gameId / "stream" =>
      withDirector(req) { claims =>
        authBridge.withTokenStreaming(claims.sub, claims.preferredUsername) { token =>
          Request[IO](
            method  = Method.GET,
            uri     = upstreamBase.withPath(Uri.Path.unsafeFromString(s"/api/tournament/$id/game/$gameId/stream")),
            headers = Headers(Header.Raw(CIString("Authorization"), s"Bearer $token"))
          )
        }
      }

    case req @ GET -> Root / "api" / "gateway" / "tournament" / id / "game" / gameId =>
      withDirector(req) { claims =>
        authBridge.withToken(claims.sub, claims.preferredUsername) { token =>
          Request[IO](
            method  = Method.GET,
            uri     = upstreamBase.withPath(Uri.Path.unsafeFromString(s"/api/tournament/$id/game/$gameId")),
            headers = Headers(Header.Raw(CIString("Authorization"), s"Bearer $token"))
          )
        }
      }
  }

  // ── Director-only (write) routes — require Keycloak auth ─────────────────────

  private val directorRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ POST -> Root / "api" / "gateway" / "tournament" =>
      withDirector(req) { claims =>
        req.bodyText.compile.string.flatMap { jsonBody =>
          jsonToFormEncoded(jsonBody) match
            case Left(err) =>
              IO.pure(errorResponse(Status.BadRequest, "BAD_REQUEST", err))
            case Right(formBody) =>
              authBridge.withToken(claims.sub, claims.preferredUsername) { token =>
                Request[IO](
                  method  = Method.POST,
                  uri     = upstreamBase.withPath(Uri.Path.unsafeFromString("/api/tournament")),
                  headers = Headers(
                    Header.Raw(CIString("Content-Type"), "application/x-www-form-urlencoded"),
                    Header.Raw(CIString("Authorization"), s"Bearer $token")
                  ),
                  body = Stream.emits(formBody.getBytes("UTF-8")).covary[IO]
                )
              }
        }
      }

    case req @ POST -> Root / "api" / "gateway" / "tournament" / id / "start" =>
      withDirector(req) { claims =>
        authBridge.withToken(claims.sub, claims.preferredUsername) { token =>
          Request[IO](
            method  = Method.POST,
            uri     = upstreamBase.withPath(Uri.Path.unsafeFromString(s"/api/tournament/$id/start")),
            headers = Headers(Header.Raw(CIString("Authorization"), s"Bearer $token"))
          )
        }
      }

    case req @ DELETE -> Root / "api" / "gateway" / "tournament" / id =>
      withDirector(req) { claims =>
        authBridge.withToken(claims.sub, claims.preferredUsername) { token =>
          Request[IO](
            method  = Method.DELETE,
            uri     = upstreamBase.withPath(Uri.Path.unsafeFromString(s"/api/tournament/$id")),
            headers = Headers(Header.Raw(CIString("Authorization"), s"Bearer $token"))
          )
        }
      }

    case req @ POST -> Root / "api" / "gateway" / "tournament" / id / "participants" =>
      withDirector(req) { claims =>
        req.bodyText.compile.string.flatMap { jsonBody =>
          authBridge.withToken(claims.sub, claims.preferredUsername) { token =>
            Request[IO](
              method  = Method.POST,
              uri     = upstreamBase.withPath(Uri.Path.unsafeFromString(s"/api/tournament/$id/participants")),
              headers = Headers(
                Header.Raw(CIString("Content-Type"), "application/json"),
                Header.Raw(CIString("Authorization"), s"Bearer $token")
              ),
              body = Stream.emits(jsonBody.getBytes("UTF-8")).covary[IO]
            )
          }
        }
      }
  }

  // ── Public read-only proxy routes ─────────────────────────────────────────────

  private val gatewayRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "api" / "gateway" / "bots" =>
      proxyJson("/api/bots")

    case GET -> Root / "api" / "gateway" / "openings" =>
      proxyJson("/api/openings")

    case GET -> Root / "api" / "gateway" / "tournament" =>
      proxyJson("/api/tournament")

    case GET -> Root / "api" / "gateway" / "tournament" / id / "analytics-export" =>
      proxyJson(s"/api/tournament/$id/analytics-export")

    case GET -> Root / "api" / "gateway" / "tournament" / id / "results" =>
      proxyNdjsonAsJsonArray(s"/api/tournament/$id/results")

    case GET -> Root / "api" / "gateway" / "tournament" / id / "round" / round =>
      proxyJson(s"/api/tournament/$id/round/$round")

    case GET -> Root / "api" / "gateway" / "tournament" / id =>
      proxyJson(s"/api/tournament/$id")
  }

  // Declared after all sub-routes so each sub-route is already initialized when
  // the <+> combinator captures them (Scala vals initialize in textual order).
  val routes: HttpRoutes[IO] = operationalRoutes <+> streamRoutes <+> directorRoutes <+> gatewayRoutes

  // ── Helpers: public proxy ─────────────────────────────────────────────────────

  private def proxyJson(path: String): IO[Response[IO]] =
    val uri = upstreamBase.withPath(Uri.Path.unsafeFromString(path))
    client.run(Request[IO](Method.GET, uri)).use { upstream =>
      upstream.as[String].map { body =>
        buildResponse(upstream.status, body.getBytes("UTF-8"))
      }
    }.handleErrorWith { _ => IO.pure(unavailableResponse) }

  private def proxyNdjsonAsJsonArray(path: String): IO[Response[IO]] =
    val uri = upstreamBase.withPath(Uri.Path.unsafeFromString(path))
    client.run(Request[IO](Method.GET, uri)).use { upstream =>
      upstream.as[String].map { body =>
        val arr = parseNdjsonOrArray(body)
        buildResponse(upstream.status, ujson.write(arr).getBytes("UTF-8"))
      }
    }.handleErrorWith { _ => IO.pure(unavailableResponse) }

  private def parseNdjsonOrArray(body: String): ujson.Value =
    val trimmed = body.trim
    if trimmed.startsWith("[") then
      try ujson.read(trimmed)
      catch case _: Exception => ujson.Arr()
    else
      val lines = trimmed.split("\n").filter(_.nonEmpty)
      try ujson.Arr(lines.map(ujson.read(_))*)
      catch case _: Exception => ujson.Arr()

  // ── Helpers: auth ─────────────────────────────────────────────────────────────

  /** Resolves the Keycloak identity for director-only routes.
    *
    * In dev mode (authDisabled=true): uses the configured dev identity. No JWT is decoded.
    * In prod mode: extracts claims from the Envoy-forwarded Keycloak JWT. Rejects on missing
    * or malformed token. Does not re-verify the JWT signature — Envoy owns that.
    */
  private def withDirector(req: Request[IO])(
    f: GatewayJwtClaims => IO[Response[IO]]
  ): IO[Response[IO]] =
    if config.authDisabled then
      f(GatewayJwtClaims(sub = "dev-sub", preferredUsername = config.devUserName, email = None))
    else
      req.headers.get(CIString("Authorization")).map(_.head.value) match
        case None =>
          IO.pure(errorResponse(Status.Unauthorized, "UNAUTHORIZED", "Missing Authorization header"))
        case Some(headerValue) =>
          GatewayJwtExtractor.fromBearerHeader(headerValue) match
            case Left(err)     => IO.pure(errorResponse(Status.Unauthorized, "UNAUTHORIZED", err))
            case Right(claims) => f(claims)

  // ── Helpers: form encoding ────────────────────────────────────────────────────

  private def jsonToFormEncoded(body: String): Either[String, String] =
    try
      val json   = ujson.read(body)
      val fields = json.obj.collect {
        case (k, v: ujson.Str)  => k -> v.str
        case (k, ujson.Num(n))  =>
          k -> (if n == math.floor(n) && !n.isInfinite then n.toLong.toString else n.toString)
        case (k, ujson.Bool(b)) => k -> b.toString
      }.toMap
      Right(encodeForm(fields))
    catch
      case NonFatal(_) => Left("Tournament creation request body must be a valid JSON object")

  private def encodeForm(fields: Map[String, String]): String =
    fields.map { (k, v) =>
      java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
    }.mkString("&")

  // ── Helpers: response builders ────────────────────────────────────────────────

  private def buildResponse(status: Status, bytes: Array[Byte]): Response[IO] =
    Response[IO](
      status  = status,
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body    = Stream.emits(bytes).covary[IO]
    )

  private def errorResponse(status: Status, code: String, message: String): Response[IO] =
    buildResponse(status, ujson.write(ujson.Obj("code" -> code, "message" -> message)).getBytes("UTF-8"))

  private def jsonOk(body: ujson.Value): IO[Response[IO]] =
    IO.pure(buildResponse(Status.Ok, ujson.write(body).getBytes("UTF-8")))

  private val unavailableResponse: Response[IO] =
    buildResponse(
      Status.ServiceUnavailable,
      """{"code":"TOURNAMENT_SERVER_UNAVAILABLE","message":"Tournament server is currently unavailable"}"""
        .getBytes("UTF-8")
    )
