package chess.userservice

import cats.effect.IO
import chess.observability.StructuredLog
import chess.userservice.application.{CreateChallengeRequest, JwtSubjectExtractor, LichessActiveGameSummary, LichessChallengeService, LichessGameStateResult, LichessOAuthConfig, LichessOAuthService, PublicTournamentParticipantRepository, SubmitLichessMoveResult, TournamentBotOwnershipRepository, UserProfileService}
import chess.userservice.domain.{ExternalAccountLink, PublicTournamentParticipant, TournamentBotOwnership, UserProfile}
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.{Location, `Content-Type`}
import org.typelevel.ci.CIString

import java.util.UUID

/** HTTP routes for user-service.
  *
  * Path convention (after Envoy prefix rewrite /api/users → /users):
  *   GET    /health                                         — liveness (no JWT)
  *   GET    /users/me                                       — current user profile + links
  *   PATCH  /users/me/profile                              — set Searchess nickname
  *   PUT    /users/me/links/lichess/manual                 — set Lichess username (ManualDev, dev fallback)
  *   DELETE /users/me/links/lichess                        — remove Lichess link
  *   GET    /users/me/links/lichess/start                  — begin Lichess OAuth PKCE flow (JWT required)
  *   POST   /users/me/links/lichess/upgrade                — begin Lichess OAuth upgrade flow (JWT required)
  *   GET    /users/me/links/lichess/callback               — OAuth callback from Lichess (no JWT, browser redirect)
  *   POST   /users/me/lichess/challenges/searchess-bot     — create Lichess challenge to Searchess BOT (challenge_ready only)
  *   GET    /users/me/tournament-bots                      — list owned tournament-server bot IDs
  *   POST   /users/me/tournament-bots                      — record ownership of a tournament-server bot
  *   GET    /users/tournaments/{tournamentId}/participants  — list joined participants (public), enriched with searchessCatalogBotId
  *   POST   /users/tournaments/{tournamentId}/participants  — record participant join (auth required)
  */
class UserRoutes(
    service: UserProfileService,
    oauthService: LichessOAuthService,
    challengeService: LichessChallengeService,
    lichessConfig: LichessOAuthConfig,
    botOwnershipRepo: TournamentBotOwnershipRepository,
    participantRepo: PublicTournamentParticipantRepository
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

    case req @ GET -> Root / "users" / "me" / "lichess" / "games" / "active" =>
      withSubject(req) { (_, profile) =>
        challengeService.getActiveGamesVsBot(profile.userId).flatMap {
          case Left("no_lichess_link") =>
            respond(Status.Forbidden, ujson.Obj("code" -> "NO_LICHESS_LINK"))
          case Left("no_lichess_game_capability") =>
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
          case Left(_) =>
            respond(Status.BadGateway, ujson.Obj("code" -> "LICHESS_ACTIVE_GAMES_FAILED"))
          case Right(games) =>
            respond(Status.Ok, ujson.Obj("games" -> ujson.Arr(games.map(lichessActiveGameSummaryJson)*)))
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

    case req @ GET -> Root / "users" / "me" / "tournament-bots" =>
      withSubject(req) { (_, profile) =>
        botOwnershipRepo.findAllByUserId(profile.userId) match
          case Left(err)   => respond(Status.InternalServerError, ujson.Obj("code" -> "INTERNAL_ERROR", "message" -> err))
          case Right(bots) => respond(Status.Ok, ujson.Obj("bots" -> ujson.Arr(bots.map(botOwnershipJson)*)))
      }

    case req @ POST -> Root / "users" / "me" / "tournament-bots" =>
      withSubject(req) { (_, profile) =>
        req.bodyText.compile.string.flatMap { body =>
          parseRecordBotRequest(body) match
            case Left(err) =>
              respond(Status.BadRequest, ujson.Obj("code" -> "BAD_REQUEST", "message" -> err))
            case Right((tsBotId, tsBotName, catalogBotId)) =>
              val ownership = TournamentBotOwnership(UUID.randomUUID(), profile.userId, tsBotId, tsBotName, catalogBotId, java.time.Instant.now())
              botOwnershipRepo.insertIfAbsent(ownership) match
                case Left(err) =>
                  respond(Status.InternalServerError, ujson.Obj("code" -> "INTERNAL_ERROR", "message" -> err))
                case Right(saved) =>
                  respond(Status.Created, botOwnershipJson(saved))
        }
      }

    case GET -> Root / "users" / "tournaments" / tournamentId / "participants" =>
      participantRepo.findByTournamentId(tournamentId) match
        case Left(err) =>
          respond(Status.InternalServerError, ujson.Obj("code" -> "INTERNAL_ERROR", "message" -> err))
        case Right(participants) =>
          val ownershipIds = participants.flatMap(_.searchessBotId).toSet
          val catalogIdMap: Map[UUID, String] =
            if ownershipIds.isEmpty then Map.empty
            else botOwnershipRepo.findByIdIn(ownershipIds) match
              case Left(_)      => Map.empty
              case Right(items) => items.flatMap(o => o.searchessCatalogBotId.map(cid => o.id -> cid)).toMap
          respond(Status.Ok, ujson.Obj(
            "tournamentId" -> tournamentId,
            "participants" -> ujson.Arr(participants.map { p =>
              val catalogId = p.searchessBotId.flatMap(catalogIdMap.get)
              participantJson(p, catalogId)
            }*)
          ))

    case req @ POST -> Root / "users" / "tournaments" / tournamentId / "participants" =>
      withSubject(req) { (_, profile) =>
        req.bodyText.compile.string.flatMap { body =>
          parseAddParticipantRequest(body) match
            case Left(err) =>
              respond(Status.BadRequest, ujson.Obj("code" -> "BAD_REQUEST", "message" -> err))
            case Right((tsBotId, tsBotName, tsUserId)) =>
              System.err.println(s"[PARTICIPANT] REQUEST tournamentId='$tournamentId' userId='${profile.userId}' tsBotId='$tsBotId' tsBotName='$tsBotName' tsUserId='$tsUserId'")
              botOwnershipRepo.findAllByUserId(profile.userId) match
                case Left(err) =>
                  respond(Status.InternalServerError, ujson.Obj("code" -> "INTERNAL_ERROR", "message" -> err))
                case Right(ownedBots) =>
                  ownedBots.find(_.tournamentServerBotId == tsBotId) match
                    case None =>
                      respond(Status.Forbidden, ujson.Obj("code" -> "BOT_NOT_OWNED", "message" -> s"Bot '$tsBotId' is not registered to this user"))
                    case Some(matchingOwnership) =>
                      val participant = PublicTournamentParticipant(
                        tournamentId            = tournamentId,
                        searchessUserId         = profile.userId,
                        displayName             = profile.displayName,
                        searchessBotId          = Some(matchingOwnership.id),
                        tournamentServerUserId  = tsUserId,
                        tournamentServerBotId   = tsBotId,
                        tournamentServerBotName = tsBotName,
                        joinedAt                = java.time.Instant.now()
                      )
                      participantRepo.insertIfAbsent(participant) match
                        case Left("user_already_joined_with_different_bot") =>
                          respond(Status.Conflict, ujson.Obj("code" -> "USER_ALREADY_JOINED", "message" -> "You have already joined this tournament with a different bot"))
                        case Left("bot_already_claimed_by_another_user") =>
                          respond(Status.Conflict, ujson.Obj("code" -> "BOT_ALREADY_CLAIMED", "message" -> s"Bot '$tsBotId' is already registered for this tournament by another user"))
                        case Left(err) =>
                          respond(Status.InternalServerError, ujson.Obj("code" -> "INTERNAL_ERROR", "message" -> err))
                        case Right(saved) =>
                          System.err.println(s"[PARTICIPANT] SAVED tournamentId='$tournamentId' searchessUserId='${saved.searchessUserId}' searchessBotId='${saved.searchessBotId}' tsBotId='${saved.tournamentServerBotId}' tsBotName='${saved.tournamentServerBotName}'")
                          respond(Status.Created, participantJson(saved, matchingOwnership.searchessCatalogBotId))
        }
      }

    case req @ POST -> Root / "users" / "me" / "lichess" / "games" / gameId / "move" =>
      withSubject(req) { (_, profile) =>
        req.bodyText.compile.string.flatMap { body =>
          parseMoveRequest(body) match
            case Left(err) =>
              respond(Status.BadRequest, ujson.Obj("code" -> "INVALID_MOVE_FORMAT", "message" -> err))
            case Right(move) =>
              challengeService.submitMove(profile.userId, gameId, move).flatMap {
                case Left("no_lichess_link") =>
                  respond(Status.Forbidden, ujson.Obj("code" -> "NO_LICHESS_LINK"))
                case Left("no_lichess_game_capability") =>
                  respond(Status.Forbidden, ujson.Obj("code" -> "NO_CHALLENGE_READY_CAPABILITY"))
                case Left("no_stored_lichess_token") =>
                  respond(Status.Forbidden, ujson.Obj("code" -> "NO_STORED_LICHESS_TOKEN"))
                case Left("token_encryption_not_configured") =>
                  respond(Status.ServiceUnavailable, ujson.Obj(
                    "code"    -> "TOKEN_ENCRYPTION_NOT_CONFIGURED",
                    "message" -> "Lichess token encryption is not configured."
                  ))
                case Left("invalid_move_format") =>
                  respond(Status.BadRequest, ujson.Obj("code" -> "INVALID_MOVE_FORMAT"))
                case Left("not_user_turn") =>
                  respond(Status.Conflict, ujson.Obj("code" -> "NOT_USER_TURN"))
                case Left("illegal_or_invalid_move") =>
                  respond(Status.UnprocessableEntity, ujson.Obj("code" -> "ILLEGAL_OR_INVALID_MOVE"))
                case Left("lichess_token_expired") =>
                  respond(Status.Forbidden, ujson.Obj("code" -> "LICHESS_TOKEN_EXPIRED"))
                case Left(err) if err.startsWith("invalid_lichess_game_id:") =>
                  respond(Status.BadRequest, ujson.Obj("code" -> "INVALID_LICHESS_GAME_ID", "message" -> err.stripPrefix("invalid_lichess_game_id:")))
                case Left(_) =>
                  respond(Status.BadGateway, ujson.Obj("code" -> "LICHESS_MOVE_FAILED"))
                case Right(result) =>
                  respond(Status.Ok, submitMoveJson(result))
              }
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

  private def parseMoveRequest(body: String): Either[String, String] =
    try
      val json = ujson.read(body)
      json.obj.get("move").flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty) match
        case None       => Left("Missing or empty 'move' field")
        case Some(move) => Right(move)
    catch
      case _: Exception => Left("Request body must be valid JSON with a 'move' string field")

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

  private def lichessActiveGameSummaryJson(game: LichessActiveGameSummary): ujson.Value =
    ujson.Obj(
      "gameId"        -> game.gameId,
      "status"        -> game.status,
      "url"           -> game.url,
      "white"         -> ujson.Obj(
        "username"       -> game.white.username,
        "isSearchessBot" -> game.white.isSearchessBot
      ),
      "black"         -> ujson.Obj(
        "username"       -> game.black.username,
        "isSearchessBot" -> game.black.isSearchessBot
      ),
      "userColor"     -> game.userColor.map(ujson.Str(_)).getOrElse(ujson.Null),
      "botColor"      -> game.botColor.map(ujson.Str(_)).getOrElse(ujson.Null),
      "lastUpdatedAt" -> game.lastUpdatedAt.toString
    )

  private def submitMoveJson(result: SubmitLichessMoveResult): ujson.Value =
    ujson.Obj(
      "gameId"   -> result.gameId,
      "move"     -> result.move,
      "accepted" -> result.accepted
    )

  private def parseRecordBotRequest(body: String): Either[String, (String, String, Option[String])] =
    try
      val json      = ujson.read(body)
      val botId     = json.obj.get("tournamentServerBotId").flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty)
      val name      = json.obj.get("tournamentServerBotName").flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty)
      val catalogId = json.obj.get("searchessCatalogBotId").flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty)
      (botId, name) match
        case (Some(id), Some(n)) => Right((id, n, catalogId))
        case (None, _)           => Left("Missing or empty 'tournamentServerBotId' field")
        case (_, None)           => Left("Missing or empty 'tournamentServerBotName' field")
    catch
      case _: Exception => Left("Request body must be valid JSON with 'tournamentServerBotId' and 'tournamentServerBotName' string fields")

  private def botOwnershipJson(o: TournamentBotOwnership): ujson.Value =
    ujson.Obj(
      "searchessBotId"          -> o.id.toString,
      "tournamentServerBotId"   -> o.tournamentServerBotId,
      "tournamentServerBotName" -> o.tournamentServerBotName,
      "searchessCatalogBotId"   -> o.searchessCatalogBotId.map(ujson.Str(_)).getOrElse(ujson.Null),
      "createdAt"               -> o.createdAt.toString
    )

  private def parseAddParticipantRequest(body: String): Either[String, (String, String, Option[String])] =
    try
      val json   = ujson.read(body)
      val botId  = json.obj.get("tournamentServerBotId").flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty)
      val name   = json.obj.get("tournamentServerBotName").flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty)
      val tsUser = json.obj.get("tournamentServerUserId").flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty)
      (botId, name) match
        case (Some(id), Some(n)) => Right((id, n, tsUser))
        case (None, _)           => Left("Missing or empty 'tournamentServerBotId' field")
        case (_, None)           => Left("Missing or empty 'tournamentServerBotName' field")
    catch
      case _: Exception => Left("Request body must be valid JSON with 'tournamentServerBotId' and 'tournamentServerBotName' string fields")

  private def participantJson(p: PublicTournamentParticipant, searchessCatalogBotId: Option[String]): ujson.Value =
    ujson.Obj(
      "tournamentId"            -> p.tournamentId,
      "searchessUserId"         -> p.searchessUserId.toString,
      "displayName"             -> p.displayName,
      "searchessBotId"          -> p.searchessBotId.map(_.toString).map(ujson.Str(_)).getOrElse(ujson.Null),
      "searchessCatalogBotId"   -> searchessCatalogBotId.map(ujson.Str(_)).getOrElse(ujson.Null),
      "tournamentServerUserId"  -> p.tournamentServerUserId.map(ujson.Str(_)).getOrElse(ujson.Null),
      "tournamentServerBotId"   -> p.tournamentServerBotId,
      "tournamentServerBotName" -> p.tournamentServerBotName,
      "joinedAt"                -> p.joinedAt.toString
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
