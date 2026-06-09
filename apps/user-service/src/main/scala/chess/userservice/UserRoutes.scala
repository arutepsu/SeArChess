package chess.userservice

import cats.effect.IO
import chess.observability.StructuredLog
import chess.userservice.application.{CreateChallengeRequest, JwtSubjectExtractor, LichessChallengeService, LichessGameStateResult, LichessOAuthConfig, LichessOAuthService, UserProfileService}
import chess.userservice.domain.{ExternalAccountLink, UserProfile}
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.{Location, `Content-Type`}
import org.typelevel.ci.CIString

/** HTTP routes for user-service.
  *
  * Path convention (after Envoy prefix rewrite /api/users → /users):
  *   GET    /health                                    — liveness (no JWT)
  *   GET    /users/me                                  — current user profile + links
  *   PATCH  /users/me/profile                          — set Searchess nickname
  *   PUT    /users/me/links/lichess/manual             — set Lichess username (ManualDev, dev fallback)
  *   DELETE /users/me/links/lichess                    — remove Lichess link
  *   GET    /users/me/links/lichess/start              — begin Lichess OAuth PKCE flow (JWT required)
  *   POST   /users/me/links/lichess/upgrade            — begin Lichess OAuth upgrade flow (JWT required)
  *   GET    /users/me/links/lichess/callback           — OAuth callback from Lichess (no JWT, browser redirect)
  *   POST   /users/me/lichess/challenges/searchess-bot — create Lichess challenge to Searchess BOT (challenge_ready only)
  */
class UserRoutes(
    service: UserProfileService,
    oauthService: LichessOAuthService,
    challengeService: LichessChallengeService,
    lichessConfig: LichessOAuthConfig
):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "health" =>
      respond(Status.Ok, ujson.Obj("status" -> "ok", "service" -> "searchess-user-service"))

    case req @ GET -> Root / "users" / "me" =>
      withSubject(req) { (_, profile) =>
        respondWithProfile(profile)
      }

    case req @ PATCH -> Root / "users" / "me" / "profile" =>
      withSubject(req) { (_, profile) =>
        req.bodyText.compile.string.flatMap { body =>
          parseNicknameRequest(body) match
            case Left(err)      =>
              respond(Status.UnprocessableEntity, ujson.Obj("code" -> "VALIDATION_ERROR", "message" -> err))
            case Right(rawNick) =>
              service.setNickname(profile, rawNick) match
                case Left(err) =>
                  val status = if err.contains("already taken") then Status.Conflict else Status.UnprocessableEntity
                  respond(status, ujson.Obj("code" -> "VALIDATION_ERROR", "message" -> err))
                case Right(updated) =>
                  respondWithProfile(updated)
        }
      }

    case req @ PUT -> Root / "users" / "me" / "links" / "lichess" / "manual" =>
      withSubject(req) { (_, profile) =>
        req.bodyText.compile.string.flatMap { body =>
          parseLichessUsername(body) match
            case Left(err) =>
              respond(Status.BadRequest, ujson.Obj("code" -> "BAD_REQUEST", "message" -> err))
            case Right(lichessUsername) =>
              service.setManualLichessLink(profile.userId, lichessUsername) match
                case Left(err)   => respond(Status.InternalServerError, ujson.Obj("code" -> "INTERNAL_ERROR", "message" -> err))
                case Right(link) => respond(Status.Ok, linkJson(link))
        }
      }

    case req @ DELETE -> Root / "users" / "me" / "links" / "lichess" =>
      withSubject(req) { (_, profile) =>
        service.deleteLink(profile.userId, "Lichess") match
          case Left(err) => respond(Status.InternalServerError, ujson.Obj("code" -> "INTERNAL_ERROR", "message" -> err))
          case Right(_)  => IO.pure(Response[IO](status = Status.NoContent))
      }

    case req @ GET -> Root / "users" / "me" / "links" / "lichess" / "start" =>
      withSubject(req) { (_, profile) =>
        oauthService.createLinkStart(profile.userId) match
          case Left(err)  =>
            respond(Status.ServiceUnavailable, ujson.Obj("code" -> "OAUTH_NOT_CONFIGURED", "message" -> err))
          case Right(url) =>
            respond(Status.Ok, ujson.Obj("authorizationUrl" -> url))
      }

    case req @ POST -> Root / "users" / "me" / "links" / "lichess" / "upgrade" =>
      withSubject(req) { (_, profile) =>
        req.bodyText.compile.string.flatMap { body =>
          parseTargetCapability(body) match
            case Left(err) =>
              respond(Status.BadRequest, ujson.Obj("code" -> "BAD_REQUEST", "message" -> err))
            case Right(targetCapability) =>
              oauthService.createUpgradeStart(profile.userId, targetCapability) match
                case Left("unsupported_target_capability") =>
                  respond(Status.BadRequest, ujson.Obj("code" -> "UNSUPPORTED_TARGET", "message" -> "unsupported_target_capability"))
                case Left(err) =>
                  respond(Status.ServiceUnavailable, ujson.Obj("code" -> "OAUTH_NOT_CONFIGURED", "message" -> err))
                case Right(url) =>
                  respond(Status.Ok, ujson.Obj("authorizationUrl" -> url))
        }
      }

    case req @ GET -> Root / "users" / "me" / "links" / "lichess" / "callback" =>
      val params = req.uri.query.params
      (params.get("code"), params.get("state")) match
        case (Some(code), Some(state)) =>
          oauthService.exchangeCallback(code, state).map {
            case Left(err) =>
              StructuredLog.warn("user-service", "oauth_callback_failed", "error" -> err)
              redirect(lichessConfig.webUiSettingsUrl + "?lichess=failed")
            case Right(link) =>
              val suffix = if link.capability == "challenge_ready" then "?lichess=upgraded" else "?lichess=linked"
              redirect(lichessConfig.webUiSettingsUrl + suffix)
          }
        case _ =>
          IO.pure(redirect(lichessConfig.webUiSettingsUrl + "?lichess=failed"))

    case req @ POST -> Root / "users" / "me" / "lichess" / "challenges" / "searchess-bot" =>
      withSubject(req) { (_, profile) =>
        req.bodyText.compile.string.flatMap { body =>
          parseChallengeRequest(body) match
            case Left(err) =>
              respond(Status.BadRequest, ujson.Obj("code" -> "INVALID_CHALLENGE_REQUEST", "message" -> err))
            case Right(challengeReq) =>
              challengeService.createChallengeToBot(profile.userId, challengeReq).flatMap {
                case Left("no_lichess_link") =>
                  respond(Status.Forbidden, ujson.Obj("code" -> "NO_LICHESS_LINK"))
                case Left("no_challenge_ready_capability") =>
                  respond(Status.Forbidden, ujson.Obj("code" -> "NO_CHALLENGE_READY_CAPABILITY"))
                case Left("no_stored_lichess_token") =>
                  respond(Status.Forbidden, ujson.Obj("code" -> "NO_STORED_LICHESS_TOKEN"))
                case Left("token_encryption_not_configured") =>
                  respond(Status.ServiceUnavailable, ujson.Obj(
                    "code"    -> "TOKEN_ENCRYPTION_NOT_CONFIGURED",
                    "message" -> "Lichess token encryption is not configured."
                  ))
                case Left("lichess_token_expired") =>
                  respond(Status.Forbidden, ujson.Obj("code" -> "LICHESS_TOKEN_EXPIRED"))
                case Left(err) if err.startsWith("invalid_challenge_request:") =>
                  respond(Status.BadRequest, ujson.Obj("code" -> "INVALID_CHALLENGE_REQUEST", "message" -> err.stripPrefix("invalid_challenge_request:")))
                case Left(_) =>
                  respond(Status.BadGateway, ujson.Obj("code" -> "LICHESS_CHALLENGE_FAILED"))
                case Right(result) =>
                  respond(Status.Ok, ujson.Obj("challengeId" -> result.challengeId, "url" -> result.url))
              }
        }
      }

    case req @ GET -> Root / "users" / "me" / "lichess" / "games" / gameId =>
      withSubject(req) { (_, profile) =>
        challengeService.getReadOnlyGameState(profile.userId, gameId).flatMap {
          case Left("no_lichess_link") =>
            respond(Status.Forbidden, ujson.Obj("code" -> "NO_LICHESS_LINK"))
          case Left("no_lichess_game_capability") =>
            respond(Status.Forbidden, ujson.Obj("code" -> "NO_LICHESS_GAME_CAPABILITY"))
          case Left("no_stored_lichess_token") =>
            respond(Status.Forbidden, ujson.Obj("code" -> "NO_STORED_LICHESS_TOKEN"))
          case Left("token_encryption_not_configured") =>
            respond(Status.ServiceUnavailable, ujson.Obj(
              "code"    -> "TOKEN_ENCRYPTION_NOT_CONFIGURED",
              "message" -> "Lichess token encryption is not configured."
            ))
          case Left("lichess_token_expired") =>
            respond(Status.Forbidden, ujson.Obj("code" -> "LICHESS_TOKEN_EXPIRED"))
          case Left(err) if err.startsWith("invalid_lichess_game_id:") =>
            respond(Status.BadRequest, ujson.Obj("code" -> "INVALID_LICHESS_GAME_ID", "message" -> err.stripPrefix("invalid_lichess_game_id:")))
          case Left(_) =>
            respond(Status.BadGateway, ujson.Obj("code" -> "LICHESS_GAME_STATE_FAILED"))
          case Right(state) =>
            respond(Status.Ok, lichessGameStateJson(state))
        }
      }
  }

  private def withSubject(req: Request[IO])(
      f: (JwtSubjectExtractor.JwtClaims, UserProfile) => IO[Response[IO]]
  ): IO[Response[IO]] =
    req.headers.get(CIString("Authorization")).map(_.head.value) match
      case None =>
        respond(Status.Unauthorized, ujson.Obj("code" -> "UNAUTHORIZED", "message" -> "Missing Authorization header"))
      case Some(headerValue) =>
        JwtSubjectExtractor.fromBearerHeader(headerValue) match
          case Left(err) =>
            respond(Status.Unauthorized, ujson.Obj("code" -> "UNAUTHORIZED", "message" -> err))
          case Right(claims) =>
            service.getOrCreateProfile(claims.sub, claims.preferredUsername, claims.email) match
              case Left(err)      => respond(Status.InternalServerError, ujson.Obj("code" -> "INTERNAL_ERROR", "message" -> err))
              case Right(profile) => f(claims, profile)

  private def respondWithProfile(profile: UserProfile): IO[Response[IO]] =
    service.getLinksForUser(profile.userId) match
      case Left(err)    => respond(Status.InternalServerError, ujson.Obj("code" -> "INTERNAL_ERROR", "message" -> err))
      case Right(links) => respond(Status.Ok, profileJson(profile, links))

  private def profileJson(profile: UserProfile, links: List[ExternalAccountLink]): ujson.Value =
    ujson.Obj(
      "userId"             -> profile.userId.toString,
      "keycloakSubject"    -> profile.keycloakSubject,
      "displayName"        -> profile.displayName,
      "email"              -> profile.email.map(ujson.Str(_)).getOrElse(ujson.Null),
      "nickname"           -> profile.nickname.map(ujson.Str(_)).getOrElse(ujson.Null),
      "onboardingRequired" -> profile.onboardingRequired,
      "links"              -> ujson.Arr(links.map(linkJson)*)
    )

  private def parseNicknameRequest(body: String): Either[String, String] =
    try
      val json = ujson.read(body)
      json.obj.get("nickname").flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty) match
        case None    => Left("Missing or empty 'nickname' field")
        case Some(n) => Right(n)
    catch
      case _: Exception => Left("Request body must be valid JSON with a 'nickname' string field")

  private def parseLichessUsername(body: String): Either[String, String] =
    try
      val json = ujson.read(body)
      json.obj.get("lichessUsername").flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty) match
        case None    => Left("Missing or empty 'lichessUsername' field")
        case Some(u) => Right(u)
    catch
      case _: Exception => Left("Request body must be valid JSON with a 'lichessUsername' string field")

  private def parseTargetCapability(body: String): Either[String, String] =
    try
      val json = ujson.read(body)
      json.obj.get("targetCapability").flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty) match
        case None    => Left("Missing or empty 'targetCapability' field")
        case Some(t) => Right(t)
    catch
      case _: Exception => Left("Request body must be valid JSON with a 'targetCapability' string field")

  private def parseChallengeRequest(body: String): Either[String, CreateChallengeRequest] =
    try
      val json = ujson.read(if body.trim.isEmpty then "{}" else body)
      Right(CreateChallengeRequest(
        clockSeconds   = json.obj.get("clockSeconds").flatMap(_.numOpt).map(_.toInt).getOrElse(300),
        clockIncrement = json.obj.get("clockIncrement").flatMap(_.numOpt).map(_.toInt).getOrElse(3),
        rated          = json.obj.get("rated").flatMap(_.boolOpt).getOrElse(false),
        variant        = json.obj.get("variant").flatMap(_.strOpt).getOrElse("standard"),
        color          = json.obj.get("color").flatMap(_.strOpt).getOrElse("random")
      ))
    catch
      case _: Exception => Left("Request body must be valid JSON")

  private def linkJson(link: ExternalAccountLink): ujson.Value =
    ujson.Obj(
      "linkId"             -> link.linkId.toString,
      "provider"           -> link.provider,
      "externalId"         -> link.externalId.map(ujson.Str(_)).getOrElse(ujson.Null),
      "externalUsername"   -> link.externalUsername,
      "verified"           -> link.verified,
      "verificationSource" -> link.verificationSource,
      "linkedAt"           -> link.linkedAt.toString,
      "capability"         -> link.capability
    )

  private def lichessGameStateJson(state: LichessGameStateResult): ujson.Value =
    ujson.Obj(
      "gameId"        -> state.gameId,
      "status"        -> state.status,
      "fen"           -> state.fen.map(ujson.Str(_)).getOrElse(ujson.Null),
      "moves"         -> state.moves,
      "white"         -> ujson.Obj(
        "username"       -> state.white.username,
        "isSearchessBot" -> state.white.isSearchessBot
      ),
      "black"         -> ujson.Obj(
        "username"       -> state.black.username,
        "isSearchessBot" -> state.black.isSearchessBot
      ),
      "sideToMove"    -> state.sideToMove,
      "userColor"     -> state.userColor.map(ujson.Str(_)).getOrElse(ujson.Null),
      "botColor"      -> state.botColor.map(ujson.Str(_)).getOrElse(ujson.Null),
      "url"           -> state.url,
      "lastUpdatedAt" -> state.lastUpdatedAt.toString
    )

  private def respond(status: Status, body: ujson.Value): IO[Response[IO]] =
    IO.pure(
      Response[IO](
        status  = status,
        headers = Headers(`Content-Type`(MediaType.application.json)),
        body    = Stream.emits(ujson.write(body).getBytes("UTF-8")).covary[IO]
      )
    )

  private def redirect(url: String): Response[IO] =
    Response[IO](
      status  = Status.Found,
      headers = Headers(Location(Uri.unsafeFromString(url)))
    )
