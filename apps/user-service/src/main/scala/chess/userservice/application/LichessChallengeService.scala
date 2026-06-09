package chess.userservice.application

import cats.effect.IO
import chess.observability.StructuredLog
import chess.userservice.domain.ExternalAccountLink
import fs2.Stream
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.{Authorization, `Content-Type`}
import org.typelevel.ci.CIString

import java.net.URLEncoder
import java.util.UUID

final case class CreateChallengeRequest(
    clockSeconds: Int   = 300,
    clockIncrement: Int = 3,
    rated: Boolean      = false,
    variant: String     = "standard",
    color: String       = "random"
)

final case class CreateChallengeResult(
    challengeId: String,
    url: String
)

class LichessChallengeService(
    linkRepo: ExternalAccountLinkRepository,
    cipher: Option[LichessTokenCipher],
    client: Client[IO],
    config: LichessChallengeBotConfig
):

  def createChallengeToBot(userId: UUID, req: CreateChallengeRequest): IO[Either[String, CreateChallengeResult]] =
    validateRequest(req) match
      case Left(err) => IO.pure(Left(s"invalid_challenge_request:$err"))
      case Right(_)  =>
        IO(loadReadyLink(userId)).flatMap {
          case Left(err)   => IO.pure(Left(err))
          case Right(link) =>
            IO(decryptToken(link)).flatMap {
              case Left(err)    => IO.pure(Left(err))
              case Right(token) => postChallenge(link, token, req)
            }
        }

  private def validateRequest(req: CreateChallengeRequest): Either[String, Unit] =
    if req.rated then Left("rated must be false")
    else if req.variant != "standard" then Left("variant must be standard")
    else if req.clockSeconds < 180 || req.clockSeconds > 600 then Left("clockSeconds must be between 180 and 600")
    else if req.clockIncrement < 0 || req.clockIncrement > 10 then Left("clockIncrement must be between 0 and 10")
    else if !Set("random", "white", "black").contains(req.color) then Left("color must be random, white, or black")
    else Right(())

  private def loadReadyLink(userId: UUID): Either[String, ExternalAccountLink] =
    linkRepo.findByUserAndProvider(userId, "Lichess").flatMap {
      case None                                                => Left("no_lichess_link")
      case Some(link) if link.capability != "challenge_ready" => Left("no_challenge_ready_capability")
      case Some(link) if link.tokenEncrypted.isEmpty          => Left("no_stored_lichess_token")
      case Some(link)                                         => Right(link)
    }

  private def decryptToken(link: ExternalAccountLink): Either[String, String] =
    cipher match
      case None =>
        Left("token_encryption_not_configured")
      case Some(c) =>
        link.tokenEncrypted.fold[Either[String, String]](Left("no_stored_lichess_token"))(c.decrypt)

  private def postChallenge(
      link: ExternalAccountLink,
      token: String,
      req: CreateChallengeRequest
  ): IO[Either[String, CreateChallengeResult]] =
    val body = formEncode(
      "rated"           -> req.rated.toString,
      "clock.limit"     -> req.clockSeconds.toString,
      "clock.increment" -> req.clockIncrement.toString,
      "variant"         -> req.variant,
      "color"           -> req.color
    )
    val request = Request[IO](
      method  = Method.POST,
      uri     = Uri.unsafeFromString(config.challengeEndpointFor(config.botUsername)),
      headers = Headers(
        Authorization(Credentials.Token(AuthScheme.Bearer, token)),
        Header.Raw(CIString("Accept"), "application/json"),
        `Content-Type`(MediaType.application.`x-www-form-urlencoded`)
      ),
      body    = Stream.emits(body.getBytes("UTF-8")).covary[IO]
    )
    client.run(request).use { resp =>
      resp.bodyText.compile.string.flatMap { raw =>
        if resp.status.isSuccess then
          parseChallengeResponse(raw) match
            case Right(result) => IO.pure(Right(result))
            case Left(err)    =>
              logChallengeParseFailed(resp.status, req).as(Left(err))
        else if resp.status.code == 401 || resp.status.code == 403 then
          logChallengeFailed(resp.status, req, raw) >>
          expireLink(link).as(Left[String, CreateChallengeResult]("lichess_token_expired"))
        else
          logChallengeFailed(resp.status, req, raw).as(Left("lichess_challenge_failed"))
      }
    }.handleErrorWith(_ => IO.pure(Left("lichess_challenge_failed")))

  private def parseChallengeResponse(raw: String): Either[String, CreateChallengeResult] =
    try
      val json = ujson.read(raw)
      val challenge = json.obj.get("challenge").getOrElse(json)
      val idOpt     = challenge.obj.get("id").flatMap(_.strOpt)
      val urlOpt    = challenge.obj.get("url").flatMap(_.strOpt)
      (idOpt, urlOpt) match
        case (Some(id), Some(url)) => Right(CreateChallengeResult(id, url))
        case _                     => Left("lichess_challenge_failed")
    catch
      case _: Exception => Left("lichess_challenge_failed")

  private def logChallengeFailed(status: Status, req: CreateChallengeRequest, raw: String): IO[Unit] =
    IO(StructuredLog.warn(
      "user-service",
      "lichess_challenge_failed",
      "status"         -> status.code,
      "targetBot"      -> config.botUsername,
      "rated"          -> req.rated,
      "variant"        -> req.variant,
      "clockSeconds"   -> req.clockSeconds,
      "clockIncrement" -> req.clockIncrement,
      "color"          -> req.color,
      "responsePreview" -> sanitizedPreview(raw)
    ))

  private def logChallengeParseFailed(status: Status, req: CreateChallengeRequest): IO[Unit] =
    IO(StructuredLog.warn(
      "user-service",
      "lichess_challenge_response_parse_failed",
      "status"         -> status.code,
      "targetBot"      -> config.botUsername,
      "rated"          -> req.rated,
      "variant"        -> req.variant,
      "clockSeconds"   -> req.clockSeconds,
      "clockIncrement" -> req.clockIncrement,
      "color"          -> req.color
    ))

  private def sanitizedPreview(raw: String): String =
    raw.replaceAll("\\s+", " ").trim.take(300)

  private def expireLink(link: ExternalAccountLink): IO[Unit] =
    val expired = link.copy(
      capability     = "expired",
      tokenEncrypted = None,
      tokenScopes    = None,
      tokenStoredAt  = None
    )
    IO(linkRepo.update(expired)).attempt.flatMap {
      case Right(Right(_))  => IO.unit
      case Right(Left(err)) =>
        IO(StructuredLog.warn(
          "user-service",
          "lichess_token_expiry_update_failed",
          "userId"   -> link.userId.toString,
          "provider" -> link.provider,
          "error"    -> err
        ))
      case Left(ex) =>
        IO(StructuredLog.warn(
          "user-service",
          "lichess_token_expiry_update_failed",
          "userId"     -> link.userId.toString,
          "provider"   -> link.provider,
          "errorClass" -> ex.getClass.getSimpleName
        ))
    }

  private def formEncode(pairs: (String, String)*): String =
    pairs.map { case (k, v) => s"${encode(k)}=${encode(v)}" }.mkString("&")

  private def encode(s: String): String =
    URLEncoder.encode(s, "UTF-8")
