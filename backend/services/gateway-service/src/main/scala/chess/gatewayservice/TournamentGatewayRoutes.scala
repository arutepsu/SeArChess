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
          parseHostBotCreate(jsonBody) match
            case Left(err) =>
              IO.pure(errorResponse(Status.BadRequest, "BAD_REQUEST", err))
            case Right((hostBotId, hostBotName, formBody)) =>
              authBridge.withBotToken(hostBotId, hostBotName) { token =>
                Request[IO](
                  method  = Method.POST,
                  uri     = upstreamBase.withPath(Uri.Path.unsafeFromString("/api/tournament")),
                  headers = Headers(
                    Header.Raw(CIString("Content-Type"), "application/x-www-form-urlencoded"),
                    Header.Raw(CIString("Authorization"), s"Bearer $token")
                  ),
                  body = Stream.emits(formBody.getBytes("UTF-8")).covary[IO]
                )
              }.flatMap { createResp =>
                createResp.bodyText.compile.string.flatMap { tsCreateBody =>
                  if !createResp.status.isSuccess then
                    IO.pure(buildResponse(createResp.status, tsCreateBody.getBytes("UTF-8")))
                  else
                    parseTournamentIdFromBody(tsCreateBody) match
                      case None =>
                        IO.pure(buildResponse(Status.Ok, tsCreateBody.getBytes("UTF-8")))
                      case Some(tournamentId) =>
                        verifyAfterCreate(tournamentId, hostBotId, hostBotName, claims).flatMap {
                          case Right(()) =>
                            IO.pure(buildResponse(Status.Ok, tsCreateBody.getBytes("UTF-8")))
                          case Left(err) =>
                            IO.pure(errorResponse(Status.Conflict, "CREATE_PARTICIPANT_CONTAMINATED", err))
                        }
                }
              }
        }
      }

    case req @ POST -> Root / "api" / "gateway" / "tournament" / id / "start" =>
      withDirector(req) { claims =>
        validateParticipantsBeforeStart(id).flatMap {
          case Some(mismatchResp) => IO.pure(mismatchResp)
          case None =>
            resolveDirectorAuth(id, claims).flatMap {
              case Left(errResp) => IO.pure(errResp)
              case Right(directorBot) =>
                val mkReq: String => Request[IO] = token =>
                  Request[IO](
                    method  = Method.POST,
                    uri     = upstreamBase.withPath(Uri.Path.unsafeFromString(s"/api/tournament/$id/start")),
                    headers = Headers(Header.Raw(CIString("Authorization"), s"Bearer $token"))
                  )
                directorBot match
                  case Some((botId, botName)) => authBridge.withBotToken(botId, botName)(mkReq)
                  case None                   => authBridge.withToken(claims.sub, claims.preferredUsername)(mkReq)
            }
        }
      }

    case req @ DELETE -> Root / "api" / "gateway" / "tournament" / id =>
      withDirector(req) { claims =>
        resolveDirectorAuth(id, claims).flatMap {
          case Left(errResp) => IO.pure(errResp)
          case Right(directorBot) =>
            val mkReq: String => Request[IO] = token =>
              Request[IO](
                method  = Method.DELETE,
                uri     = upstreamBase.withPath(Uri.Path.unsafeFromString(s"/api/tournament/$id")),
                headers = Headers(Header.Raw(CIString("Authorization"), s"Bearer $token"))
              )
            directorBot match
              case Some((botId, botName)) => authBridge.withBotToken(botId, botName)(mkReq)
              case None                   => authBridge.withToken(claims.sub, claims.preferredUsername)(mkReq)
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
  // Called by the tournament-service bot runner — never by the browser.
  // Auth: static shared secret (GATEWAY_RUNNER_SECRET / Authorization: Bearer <token>),
  // not a Keycloak JWT, because tournament-service has no Keycloak service account.
  // The endpoint is only reachable cluster-internally (ClusterIP); network isolation
  // is the primary security boundary. The shared secret prevents accidental misuse.
  // Bot JWT for the tournament-server call is acquired/cached server-side.

  private val internalRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ POST -> Root / "api" / "internal" / "tournament" / id / "game" / gameId / "move" / uciMove =>
      withRunnerAuth(req) {
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

  // withRunnerAuth guards the backend-internal move submission route.
  // Accepts a static pre-shared secret (GATEWAY_RUNNER_SECRET) instead of a Keycloak JWT
  // because tournament-service is a backend service without a Keycloak service account.
  // When GATEWAY_AUTH_DISABLED=true (local dev without Keycloak) all requests pass through.
  private def withRunnerAuth(req: Request[IO])(f: IO[Response[IO]]): IO[Response[IO]] =
    if config.authDisabled then f
    else
      config.runnerSecret match
        case None =>
          IO.pure(errorResponse(Status.ServiceUnavailable, "RUNNER_NOT_CONFIGURED",
            "GATEWAY_RUNNER_SECRET is not configured"))
        case Some(secret) =>
          req.headers.get(CIString("Authorization")).map(_.head.value) match
            case None =>
              IO.pure(errorResponse(Status.Unauthorized, "UNAUTHORIZED", "Missing Authorization header"))
            case Some(v) if v == s"Bearer $secret" => f
            case _ =>
              IO.pure(errorResponse(Status.Unauthorized, "UNAUTHORIZED", "Invalid runner token"))

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

  private def encodeForm(fields: Map[String, String]): String =
    fields.map { (k, v) =>
      java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
    }.mkString("&")

  // ── Helpers: create-flow bot verification ─────────────────────────────────────

  private[gatewayservice] def parseHostBotCreate(
    body: String
  ): Either[String, (String, String, String)] =
    try
      val json    = ujson.read(body)
      val botId   = json("tournamentServerBotId").str.trim
      val botName = json("tournamentServerBotName").str.trim
      if botId.isEmpty then
        Left("tournamentServerBotId must not be empty")
      else if botName.isEmpty then
        Left("tournamentServerBotName must not be empty")
      else
        val skipKeys = Set("tournamentServerBotId", "tournamentServerBotName")
        val formFields = json.obj.collect {
          case (k, v: ujson.Str)  if !skipKeys.contains(k) => k -> v.str
          case (k, ujson.Num(n))  if !skipKeys.contains(k) =>
            k -> (if n == math.floor(n) && !n.isInfinite then n.toLong.toString else n.toString)
          case (k, ujson.Bool(b)) if !skipKeys.contains(k) => k -> b.toString
        }.toMap
        Right((botId, botName, encodeForm(formFields)))
    catch
      case NonFatal(_) =>
        Left("Tournament creation request must include tournamentServerBotId and tournamentServerBotName")

  private[gatewayservice] def parseTournamentIdFromBody(body: String): Option[String] =
    try Some(ujson.read(body)("id").str).filter(_.nonEmpty)
    catch case _: Exception => None

  private[gatewayservice] def verifyAfterCreate(
    tournamentId: String,
    hostBotId: String,
    hostBotName: String,
    claims: GatewayJwtClaims
  ): IO[Either[String, Unit]] =
    val tsDetailUri = upstreamBase.withPath(Uri.Path.unsafeFromString(s"/api/tournament/$tournamentId"))
    client.expect[String](tsDetailUri).flatMap { tsBody =>
      val bots   = parseTsBots(tsBody)
      val botIds = bots.map(_._1).toSet
      if botIds.isEmpty then
        joinHostBotAfterCreate(tournamentId, hostBotId, hostBotName).flatMap {
          case Right(())  => IO.pure(Right(()))
          case Left(err) =>
            deleteAfterContaminatedCreate(tournamentId, hostBotId, hostBotName)
              .as(Left(s"Host bot join failed after tournament creation: $err. Tournament was deleted."))
        }
      else if botIds == Set(hostBotId) then
        IO.pure(Right(()))
      else
        val unknownBots = bots.filter(_._1 != hostBotId)
        deleteAfterContaminatedCreate(tournamentId, hostBotId, hostBotName)
          .as(Left(s"Tournament creation blocked: unexpected bots in Tournament Server: ${unknownBots.map(_._2).mkString(", ")}. Tournament deleted."))
    }.handleError { err =>
      Left(s"Post-create verification failed: ${Option(err.getMessage).getOrElse("unknown")}")
    }

  private def joinHostBotAfterCreate(
    tournamentId: String,
    hostBotId: String,
    hostBotName: String
  ): IO[Either[String, Unit]] =
    authBridge.withBotToken(hostBotId, hostBotName) { token =>
      Request[IO](
        method  = Method.POST,
        uri     = upstreamBase.withPath(Uri.Path.unsafeFromString(s"/api/tournament/$tournamentId/join")),
        headers = Headers(Header.Raw(CIString("Authorization"), s"Bearer $token"))
      )
    }.flatMap { joinResp =>
      if joinResp.status.isSuccess then IO.pure(Right(()))
      else joinResp.bodyText.compile.string.map { body =>
        Left(s"Host bot join failed after create: ${joinResp.status.code} ${body.take(200)}")
      }
    }.handleError { err =>
      Left(s"Host bot join error: ${Option(err.getMessage).getOrElse("unknown")}")
    }

  private def deleteAfterContaminatedCreate(
    tournamentId: String,
    hostBotId: String,
    hostBotName: String
  ): IO[Unit] =
    authBridge.withBotToken(hostBotId, hostBotName) { token =>
      Request[IO](
        method  = Method.DELETE,
        uri     = upstreamBase.withPath(Uri.Path.unsafeFromString(s"/api/tournament/$tournamentId")),
        headers = Headers(Header.Raw(CIString("Authorization"), s"Bearer $token"))
      )
    }.void.handleError(_ => ())

  // ── Helpers: director bot identity resolution ─────────────────────────────────

  private[gatewayservice] final case class HostMetadata(
    hostKeycloakSub: Option[String],
    directorBotId: Option[String],
    directorBotName: Option[String]
  )

  // Returns None when the body is absent/unparseable (old tournaments without host record).
  // hostKeycloakSub is Option because old rows in the DB have NULL for that column.
  private[gatewayservice] def parseHostMetadata(body: String): Option[HostMetadata] =
    try
      val json = ujson.read(body)
      val sub = json.obj.get("hostKeycloakSub") match
        case None | Some(ujson.Null) => None
        case Some(v)                  => Some(v.str)
      val botId = json.obj.get("directorTournamentServerBotId") match
        case None | Some(ujson.Null) => None
        case Some(v)                  => Some(v.str)
      val botName = json.obj.get("directorTournamentServerBotName") match
        case None | Some(ujson.Null) => None
        case Some(v)                  => Some(v.str)
      Some(HostMetadata(sub, botId, botName))
    catch case _: Exception => None

  // Fetches host metadata from user-service and verifies the caller is the Searchess host.
  // Returns:
  //   Right(Some((botId, botName))) — caller verified as host; use bot JWT for TS call
  //   Right(None)                  — no host metadata or no keycloak sub stored (old row); use user JWT fallback
  //   Left(errorResponse)          — host metadata present, keycloak sub present, but caller is not the host → 403
  private[gatewayservice] def resolveDirectorAuth(
    tournamentId: String,
    claims: GatewayJwtClaims
  ): IO[Either[Response[IO], Option[(String, String)]]] =
    val hostUri = userServiceBase.withPath(
      Uri.Path.unsafeFromString(s"/users/tournaments/$tournamentId/host"))
    client.expect[String](hostUri).map { body =>
      parseHostMetadata(body) match
        case None       => Right(None)
        case Some(meta) =>
          meta.hostKeycloakSub match
            case None =>
              // Old row without keycloak sub — cannot verify caller identity; fall back to user JWT
              Right(None)
            case Some(keycloakSub) if keycloakSub != claims.sub =>
              Left(errorResponse(Status.Forbidden, "SEARCHESS_NOT_HOST",
                "You are not the Searchess host of this tournament"))
            case Some(_) =>
              (meta.directorBotId, meta.directorBotName) match
                case (Some(botId), Some(botName)) => Right(Some((botId, botName)))
                case _                             => Right(None)
    }.handleError { _ =>
      // 404 (old tournament without host row) or network error — fall back to user JWT
      Right(None)
    }

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
  //
  // Model: Searchess participants must all be present in the Tournament Server before start.
  // Extra TS bots not tracked by Searchess are treated as external public participants and allowed —
  // the Tournament Server is public and external users may join directly via its API.
  // Start is only blocked when a Searchess-chosen bot is missing from the Tournament Server.
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
      val actualIds     = tsBots.map(_._1).toSet
      val missingFromTs = searchessBots.filter { case (id, _) => !actualIds.contains(id) }
      if missingFromTs.nonEmpty then
        Some(buildResponse(Status.Conflict, ujson.write(ujson.Obj(
          "code"          -> "START_PARTICIPANT_MISMATCH",
          "message"       -> "Searchess participants have not all joined the Tournament Server",
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
