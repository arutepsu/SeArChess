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

  private val upstreamBase    = Uri.unsafeFromString(config.tournamentServerUrl)
  private val userServiceBase = Uri.unsafeFromString(config.userServiceUrl)

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
  // Director authorization is enforced by the tournament-server (start/delete check
  // t.director == userId; create sets the caller as director). The gateway only
  // authenticates the caller — it does not enforce director status itself.

  private val directorRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // Returns the current user's tournament-server identity.
    // Must be in directorRoutes (before gatewayRoutes) so "me" does not fall through to the
    // catch-all `GET /api/gateway/tournament/:id` proxy which would forward it to the external server.
    case req @ GET -> Root / "api" / "gateway" / "tournament" / "me" =>
      withDirector(req) { claims =>
        authBridge.getOrRegisterUser(claims.sub, claims.preferredUsername).flatMap {
          case Left(err) =>
            IO.pure(errorResponse(Status.ServiceUnavailable, "TOURNAMENT_AUTH_FAILED", err))
          case Right(entry) =>
            jsonOk(ujson.Obj(
              "tournamentUserId"  -> entry.tournamentUserId,
              "preferredUsername" -> claims.preferredUsername
            ))
        }
      }

    case req @ POST -> Root / "api" / "gateway" / "bots" =>
      withDirector(req) { claims =>
        req.bodyText.compile.string.flatMap { jsonBody =>
          authBridge.withToken(claims.sub, claims.preferredUsername) { token =>
            Request[IO](
              method  = Method.POST,
              uri     = upstreamBase.withPath(Uri.Path.unsafeFromString("/api/bots")),
              headers = Headers(
                Header.Raw(CIString("Content-Type"), "application/json"),
                Header.Raw(CIString("Authorization"), s"Bearer $token")
              ),
              body = Stream.emits(jsonBody.getBytes("UTF-8")).covary[IO]
            )
          }
        }
      }

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
        validateParticipantsBeforeStart(id).flatMap {
          case Some(mismatchResp) => IO.pure(mismatchResp)
          case None =>
            authBridge.withToken(claims.sub, claims.preferredUsername) { token =>
              Request[IO](
                method  = Method.POST,
                uri     = upstreamBase.withPath(Uri.Path.unsafeFromString(s"/api/tournament/$id/start")),
                headers = Headers(Header.Raw(CIString("Authorization"), s"Bearer $token"))
              )
            }
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
  }

  // ── Participant routes — require Keycloak auth; any authenticated user may attempt ──
  // The gateway does not enforce director status here. Authorization (director-only vs.
  // open join) is enforced by the tournament-server: it will return 403 for non-directors
  // calling /participants. That error propagates to the client without modification.
  //
  // POST .../join  — bot self-join via tournament-server /join endpoint.
  //   Body: { botId, botName }. The gateway acquires a bot JWT (isBot=true) for the
  //   given bot and proxies /join with no body — the tournament-server reads bot identity
  //   from the JWT. Ownership is verified by confirming that registration of (botName,
  //   isBot=true) returns the expected botId; a mismatch is rejected with BOT_ID_MISMATCH.
  //
  // POST .../participants — director-only registration path that proxies the body
  //   upstream as-is. The tournament-server enforces the director check.

  private val participantRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ POST -> Root / "api" / "gateway" / "tournament" / id / "join" =>
      withAuth(req) { _ =>
        req.bodyText.compile.string.flatMap { jsonBody =>
          parseBotJoinRequest(jsonBody) match
            case Left(err) =>
              IO.pure(errorResponse(Status.BadRequest, "BAD_REQUEST", err))
            case Right((botId, botName)) =>
              IO.delay(System.err.println(s"[JOIN] REQUEST tournamentId='$id' botId='$botId' botName='$botName'")) >>
              authBridge.withBotToken(botId, botName) { token =>
                Request[IO](
                  method  = Method.POST,
                  uri     = upstreamBase.withPath(Uri.Path.unsafeFromString(s"/api/tournament/$id/join")),
                  headers = Headers(Header.Raw(CIString("Authorization"), s"Bearer $token"))
                  // No body — tournament-server reads bot identity from JWT.
                  // DIAGNOSTIC: if wrong bot joins, the TS /join endpoint may use a different
                  // identity signal (e.g. active bot) rather than the JWT subject.
                )
              }.flatMap { resp =>
                // Idempotent join: if the tournament-server rejects because the bot is already
                // joined, treat it as success so the frontend can repair the Searchess participant
                // record.  Any other non-2xx is passed through unchanged.
                resp.bodyText.compile.string.map { body =>
                  System.err.println(s"[JOIN] RESPONSE tournamentId='$id' botId='$botId' status=${resp.status.code} body='${body.take(500)}'")
                  if resp.status.isSuccess then
                    buildResponse(Status.Ok,
                      ujson.write(ujson.Obj("joined" -> true, "alreadyJoined" -> false)).getBytes("UTF-8"))
                  else if looksLikeAlreadyJoined(body) then
                    buildResponse(Status.Ok,
                      ujson.write(ujson.Obj("joined" -> true, "alreadyJoined" -> true)).getBytes("UTF-8"))
                  else
                    buildResponse(resp.status, body.getBytes("UTF-8"))
                }
              }
        }
      }

    case req @ POST -> Root / "api" / "gateway" / "tournament" / id / "participants" =>
      withAuth(req) { claims =>
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

  // ── Backend-internal move submission route ────────────────────────────────────
  // Called by the future Searchess bot runner service — never by the browser.
  // Requires a valid Keycloak JWT (service-account token from the runner).
  // The caller resolves searchessBotId → tournamentServerBotId via user-service first.
  // Bot JWT is acquired/cached server-side and never returned to the caller.

  private val internalRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ POST -> Root / "api" / "internal" / "tournament" / id / "game" / gameId / "move" / uciMove =>
      withAuth(req) { _ =>
        req.bodyText.compile.string.flatMap { jsonBody =>
          parseBotJoinRequest(jsonBody) match
            case Left(err) =>
              IO.pure(errorResponse(Status.BadRequest, "BAD_REQUEST", err))
            case Right((botId, botName)) =>
              authBridge.submitBotMove(id, gameId, botId, botName, uciMove).map {
                case Right(()) =>
                  buildResponse(
                    Status.Ok,
                    ujson.write(ujson.Obj("accepted" -> true, "uciMove" -> uciMove)).getBytes("UTF-8")
                  )
                case Left(err) =>
                  buildResponse(
                    Status.BadGateway,
                    ujson.write(ujson.Obj("code" -> "MOVE_REJECTED", "message" -> err)).getBytes("UTF-8")
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
  val routes: HttpRoutes[IO] = operationalRoutes <+> streamRoutes <+> directorRoutes <+> participantRoutes <+> internalRoutes <+> gatewayRoutes

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

  // NOTE: withDirector is authentication only — it does NOT check whether the
  // caller is the tournament director. Director enforcement happens on the
  // tournament-server side (start and delete reject non-directors with 403).
  // The name reflects which *logical group* of routes uses it, not a Searchess-side
  // authorization check.
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

  // withAuth is semantically identical to withDirector. It is used for routes where
  // any authenticated Searchess user may call the endpoint regardless of tournament role.
  private def withAuth(req: Request[IO])(
    f: GatewayJwtClaims => IO[Response[IO]]
  ): IO[Response[IO]] = withDirector(req)(f)

  // ── Helpers: bot join ─────────────────────────────────────────────────────────

  // Heuristic: detect tournament-server "already joined" errors so the join route
  // can return 200 (idempotent) and let the caller repair the Searchess participant record.
  // Matches phrases like "already joined", "already participating", "already a member",
  // "already enrolled" — all reasonable forms a tournament-server might use.
  private def looksLikeAlreadyJoined(body: String): Boolean =
    val lower = body.toLowerCase
    lower.contains("already") &&
      (lower.contains("join") || lower.contains("participat") || lower.contains("member") || lower.contains("enrolled"))

  private def parseBotJoinRequest(body: String): Either[String, (String, String)] =
    try
      val json    = ujson.read(body)
      val botId   = json("tournamentServerBotId").str.trim
      val botName = json("tournamentServerBotName").str.trim
      if botId.isEmpty then Left("tournamentServerBotId must not be empty")
      else if botName.isEmpty then Left("tournamentServerBotName must not be empty")
      else Right((botId, botName))
    catch
      case NonFatal(_) => Left("Request body must be a JSON object with tournamentServerBotId and tournamentServerBotName")

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

  // ── Helpers: pre-start participant validation ─────────────────────────────────

  private def validateParticipantsBeforeStart(tournamentId: String): IO[Option[Response[IO]]] =
    val participantsUri = userServiceBase.withPath(
      Uri.Path.unsafeFromString(s"/users/tournaments/$tournamentId/participants"))
    val tsDetailUri = upstreamBase.withPath(
      Uri.Path.unsafeFromString(s"/api/tournament/$tournamentId"))
    for
      participantsBody <- client.expect[String](participantsUri)
        .handleError(_ => """{"participants":[]}""")
      tsDetailBody <- client.expect[String](tsDetailUri)
        .handleError(_ => """{}""")
    yield checkParticipantMismatch(participantsBody, tsDetailBody)

  // Exposed as private[gatewayservice] so unit tests in the same package can call it directly.
  private[gatewayservice] def checkParticipantMismatch(
    participantsBody: String,
    tsDetailBody: String
  ): Option[Response[IO]] =
    val searchessBots = parseSearchessParticipants(participantsBody)
    val tsBots        = parseTsBots(tsDetailBody)
    if searchessBots.isEmpty then
      Some(errorResponse(Status.Conflict, "START_PARTICIPANT_MISMATCH",
        "No Searchess participants found. Cannot verify participant integrity before start."))
    else
      val expectedIds   = searchessBots.map(_._1).toSet
      val actualIds     = tsBots.map(_._1).toSet
      val unknownInTs   = tsBots.filter { case (id, _)   => !expectedIds.contains(id) }
      val missingFromTs = searchessBots.filter { case (id, _) => !actualIds.contains(id) }
      if unknownInTs.nonEmpty || missingFromTs.nonEmpty then
        Some(buildResponse(Status.Conflict, ujson.write(ujson.Obj(
          "code"          -> "START_PARTICIPANT_MISMATCH",
          "message"       -> "Tournament Server bots do not match Searchess participants",
          "unknownInTs"   -> ujson.Arr(unknownInTs.map   { case (i, n) => ujson.Obj("id" -> i, "name" -> n): ujson.Value }*),
          "missingFromTs" -> ujson.Arr(missingFromTs.map { case (i, n) => ujson.Obj("id" -> i, "name" -> n): ujson.Value }*)
        )).getBytes("UTF-8")))
      else
        None

  private[gatewayservice] def parseSearchessParticipants(body: String): List[(String, String)] =
    try
      ujson.read(body)("participants").arr.value.toList.flatMap { p =>
        try Some((p("tournamentServerBotId").str, p("tournamentServerBotName").str))
        catch case _: Exception => None
      }
    catch case _: Exception => List.empty

  private[gatewayservice] def parseTsBots(body: String): List[(String, String)] =
    try
      val json     = ujson.read(body)
      val standing = json("standing")
      val entries =
        (try Some(standing("players").arr.value.toList) catch case _: Exception => None)
          .orElse(try Some(standing("results").arr.value.toList) catch case _: Exception => None)
          .getOrElse(List.empty)
      entries.flatMap { p =>
        try
          val bot = p("bot")
          Some((bot("id").str, bot("name").str))
        catch case _: Exception => None
      }
    catch case _: Exception => List.empty

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
