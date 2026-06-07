package chess.adapter.http4s.route

import cats.effect.IO
import chess.adapter.http4s.route.Http4sRouteSupport.*
import chess.application.bot.{BotChallengeColor, BotChallengeSession, BotChallengeStatus}
import chess.application.port.repository.{BotChallengeSessionRepository, RepositoryError}
import org.http4s.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

import java.time.Instant
import java.util.UUID
import scala.util.control.NonFatal

class Http4sBotChallengeRoutes(
    repository: BotChallengeSessionRepository,
    userClient: Option[AuthenticatedUserClient],
    botClient: Option[LichessBotChallengeClient]
):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "bot" / "challenges" =>
      handleCreate(req)
  }

  private def handleCreate(req: Request[IO]): IO[Response[IO]] =
    (userClient, botClient) match
      case (Some(users), Some(bot)) =>
        req.headers.get(CIString("Authorization")).map(_.head.value) match
          case None => jsonError(Status.Unauthorized, "UNAUTHORIZED", "Missing Authorization header")
          case Some(authHeader) =>
            users.getCurrentUser(authHeader) match
              case Left(AuthenticatedUserClientError.Unauthorized(message)) =>
                jsonError(Status.Unauthorized, "UNAUTHORIZED", message)
              case Left(AuthenticatedUserClientError.UpstreamFailure(message)) =>
                jsonError(Status.BadGateway, "USER_SERVICE_UNAVAILABLE", message)
              case Left(AuthenticatedUserClientError.InvalidResponse(message)) =>
                jsonError(Status.BadGateway, "USER_SERVICE_INVALID_RESPONSE", message)
              case Right(user) =>
                authorizeUser(user) match
                  case Left((status, code, message)) => jsonError(status, code, message)
                  case Right((nickname, link)) =>
                    req.bodyText.compile.string.flatMap { body =>
                      parseRequest(body) match
                        case Left(message) =>
                          jsonError(Status.UnprocessableEntity, "INVALID_CHALLENGE_SETTINGS", message)
                        case Right(settings) =>
                          createAndSend(user, nickname, link, settings, bot)
                    }
      case _ =>
        jsonError(Status.InternalServerError, "BOT_CHALLENGE_NOT_CONFIGURED", "Bot challenge creation is not configured")

  private def authorizeUser(
      user: AuthenticatedSearchessUser
  ): Either[(Status, String, String), (String, AuthenticatedExternalAccountLink)] =
    if user.onboardingRequired then
      Left((Status.Forbidden, "ONBOARDING_REQUIRED", "Complete onboarding before challenging the bot"))
    else
      user.nickname.map(_.trim).filter(_.nonEmpty) match
        case None => Left((Status.Forbidden, "NICKNAME_REQUIRED", "Choose a Searchess nickname before challenging the bot"))
        case Some(nickname) =>
          user.links.find(link => link.provider == "Lichess" && link.verified) match
            case Some(link) => Right((nickname, link))
            case None =>
              Left((Status.Forbidden, "LICHESS_LINK_REQUIRED", "Link and verify a Lichess account before challenging the bot"))

  private final case class ChallengeSettings(
      clockLimitSeconds: Int,
      clockIncrementSeconds: Int,
      color: BotChallengeColor,
      rated: Boolean
  )

  private def parseRequest(body: String): Either[String, ChallengeSettings] =
    try
      val json = if body.trim.isEmpty then ujson.Obj() else ujson.read(body)
      val rated = json.obj.get("rated").flatMap(_.boolOpt).getOrElse(false)
      val limit = json.obj.get("clockLimitSeconds").flatMap(_.numOpt).map(_.toInt).getOrElse(300)
      val increment = json.obj.get("clockIncrementSeconds").flatMap(_.numOpt).map(_.toInt).getOrElse(3)
      val color = json.obj.get("color").flatMap(_.strOpt).map(parseColor).getOrElse(Right(BotChallengeColor.Random))
      for
        parsedColor <- color
        _ <- Either.cond(!rated, (), "Rated challenges are not allowed in Phase 3")
        _ <- Either.cond(limit >= 60 && limit <= 10800, (), "clockLimitSeconds must be between 60 and 10800")
        _ <- Either.cond(increment >= 0 && increment <= 60, (), "clockIncrementSeconds must be between 0 and 60")
      yield ChallengeSettings(limit, increment, parsedColor, rated)
    catch case NonFatal(_) =>
      Left("Request body must be valid JSON")

  private def parseColor(raw: String): Either[String, BotChallengeColor] =
    raw.trim.toLowerCase match
      case "white" => Right(BotChallengeColor.White)
      case "black" => Right(BotChallengeColor.Black)
      case "random" => Right(BotChallengeColor.Random)
      case _ => Left("color must be white, black, or random")

  private def createAndSend(
      user: AuthenticatedSearchessUser,
      nickname: String,
      link: AuthenticatedExternalAccountLink,
      settings: ChallengeSettings,
      bot: LichessBotChallengeClient
  ): IO[Response[IO]] =
    val now = Instant.now()
    val requested = BotChallengeSession(
      id = UUID.randomUUID(),
      requestedByUserId = user.userId,
      requestedByNicknameSnapshot = nickname,
      lichessUsername = link.externalUsername,
      lichessUserId = link.externalId,
      lichessChallengeId = None,
      lichessChallengeUrl = None,
      status = BotChallengeStatus.Requested,
      clockLimitSeconds = settings.clockLimitSeconds,
      clockIncrementSeconds = settings.clockIncrementSeconds,
      color = settings.color,
      rated = false,
      createdAt = now,
      updatedAt = now,
      failureReason = None
    )

    repository.save(requested) match
      case Left(err) => persistenceError(err)
      case Right(_) =>
        bot.createChallenge(
          LichessBotChallengeRequest(
            lichessUsername = requested.lichessUsername,
            clockLimitSeconds = requested.clockLimitSeconds,
            clockIncrementSeconds = requested.clockIncrementSeconds,
            color = requested.color,
            rated = false
          )
        ) match
          case Right(response) =>
            val sent = requested.copy(
              lichessChallengeId = Some(response.challengeId),
              lichessChallengeUrl = response.url,
              status = BotChallengeStatus.Sent,
              updatedAt = Instant.now()
            )
            repository.update(sent) match
              case Left(err) => persistenceError(err)
              case Right(_)  => jsonResponse(Status.Created, sessionJson(sent))
          case Left(error) =>
            val message = botErrorMessage(error)
            val failed = requested.copy(
              status = BotChallengeStatus.Failed,
              updatedAt = Instant.now(),
              failureReason = Some(message)
            )
            val _ = repository.update(failed)
            error match
              case LichessBotChallengeClientError.Rejected(status, _) if status == 422 =>
                jsonError(Status.UnprocessableEntity, "BOT_CHALLENGE_REJECTED", message)
              case LichessBotChallengeClientError.Unavailable(_) =>
                jsonError(Status.ServiceUnavailable, "LICHESS_BOT_UNAVAILABLE", message)
              case _ =>
                jsonError(Status.BadGateway, "LICHESS_BOT_FAILED", message)

  private def persistenceError(err: RepositoryError): IO[Response[IO]] =
    jsonError(Status.InternalServerError, "PERSISTENCE_FAILED", err.toString)

  private def botErrorMessage(error: LichessBotChallengeClientError): String =
    error match
      case LichessBotChallengeClientError.Rejected(_, message) => message
      case LichessBotChallengeClientError.Unavailable(message) => message
      case LichessBotChallengeClientError.InvalidResponse(message) => message

  private def sessionJson(session: BotChallengeSession): ujson.Value =
    ujson.Obj(
      "id" -> session.id.toString,
      "requestedByNicknameSnapshot" -> session.requestedByNicknameSnapshot,
      "lichessUsername" -> session.lichessUsername,
      "lichessChallengeId" -> session.lichessChallengeId.map(ujson.Str(_)).getOrElse(ujson.Null),
      "lichessChallengeUrl" -> session.lichessChallengeUrl.map(ujson.Str(_)).getOrElse(ujson.Null),
      "status" -> session.status.toString,
      "clockLimitSeconds" -> session.clockLimitSeconds,
      "clockIncrementSeconds" -> session.clockIncrementSeconds,
      "color" -> session.color.toString,
      "rated" -> session.rated,
      "createdAt" -> session.createdAt.toString,
      "updatedAt" -> session.updatedAt.toString,
      "failureReason" -> session.failureReason.map(ujson.Str(_)).getOrElse(ujson.Null)
    )
