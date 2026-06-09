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

final case class LichessGamePlayerState(
    username: String,
    isSearchessBot: Boolean
)

final case class LichessGameStateResult(
    gameId: String,
    status: String,
    fen: Option[String],
    moves: String,
    white: LichessGamePlayerState,
    black: LichessGamePlayerState,
    sideToMove: String,
    userColor: Option[String],
    botColor: Option[String],
    url: String,
    lastUpdatedAt: java.time.Instant
)

final case class SubmitLichessMoveResult(
    gameId: String,
    move: String,
    accepted: Boolean = true
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

  def getReadOnlyGameState(userId: UUID, gameId: String): IO[Either[String, LichessGameStateResult]] =
    validateGameId(gameId) match
      case Left(err) => IO.pure(Left(s"invalid_lichess_game_id:$err"))
      case Right(_)  =>
        IO(loadGameLink(userId)).flatMap {
          case Left(err)   => IO.pure(Left(err))
          case Right(link) =>
            IO(decryptToken(link)).flatMap {
              case Left(err)    => IO.pure(Left(err))
              case Right(token) => fetchGameState(link, token, gameId)
            }
        }

  def submitMove(userId: UUID, gameId: String, move: String): IO[Either[String, SubmitLichessMoveResult]] =
    (validateGameId(gameId), validateMove(move)) match
      case (Left(err), _) => IO.pure(Left(s"invalid_lichess_game_id:$err"))
      case (_, Left(_))   => IO.pure(Left("invalid_move_format"))
      case (Right(_), Right(normalizedMove)) =>
        IO(loadGameLink(userId)).flatMap {
          case Left(err)   => IO.pure(Left(err))
          case Right(link) =>
            IO(decryptToken(link)).flatMap {
              case Left(err)    => IO.pure(Left(err))
              case Right(token) => postMove(link, token, gameId, normalizedMove)
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

  private def loadGameLink(userId: UUID): Either[String, ExternalAccountLink] =
    linkRepo.findByUserAndProvider(userId, "Lichess").flatMap {
      case None => Left("no_lichess_link")
      case Some(link) if !Set("challenge_ready", "board_play").contains(link.capability) =>
        Left("no_lichess_game_capability")
      case Some(link) if link.tokenEncrypted.isEmpty => Left("no_stored_lichess_token")
      case Some(link)                                => Right(link)
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

  private def fetchGameState(
      link: ExternalAccountLink,
      token: String,
      gameId: String
  ): IO[Either[String, LichessGameStateResult]] =
    val request = Request[IO](
      method  = Method.GET,
      uri     = Uri.unsafeFromString(config.gameExportEndpointFor(gameId)),
      headers = Headers(
        Authorization(Credentials.Token(AuthScheme.Bearer, token)),
        Header.Raw(CIString("Accept"), "application/json")
      )
    )
    client.run(request).use { resp =>
      resp.bodyText.compile.string.flatMap { raw =>
        if resp.status.isSuccess then
          parseGameStateResponse(raw, gameId, link) match
            case Right(result) => IO.pure(Right(result))
            case Left(err)    =>
              logGameStateParseFailed(resp.status, gameId).as(Left(err))
        else if resp.status.code == 401 || resp.status.code == 403 then
          logGameStateFailed(resp.status, gameId, raw) >>
          expireLink(link).as(Left[String, LichessGameStateResult]("lichess_token_expired"))
        else
          logGameStateFailed(resp.status, gameId, raw).as(Left("lichess_game_state_failed"))
      }
    }.handleErrorWith(_ => IO.pure(Left("lichess_game_state_failed")))

  private def postMove(
      link: ExternalAccountLink,
      token: String,
      gameId: String,
      move: String
  ): IO[Either[String, SubmitLichessMoveResult]] =
    val request = Request[IO](
      method  = Method.POST,
      uri     = Uri.unsafeFromString(config.boardMoveEndpointFor(gameId, move)),
      headers = Headers(
        Authorization(Credentials.Token(AuthScheme.Bearer, token)),
        Header.Raw(CIString("Accept"), "application/json")
      )
    )
    client.run(request).use { resp =>
      resp.bodyText.compile.string.flatMap { raw =>
        if resp.status.isSuccess then
          IO.pure(Right(SubmitLichessMoveResult(gameId, move)))
        else if resp.status.code == 400 || resp.status.code == 422 then
          logMoveFailed(resp.status, gameId, move, raw).as(Left("illegal_or_invalid_move"))
        else if resp.status.code == 401 || resp.status.code == 403 then
          logMoveFailed(resp.status, gameId, move, raw) >>
          expireLink(link).as(Left[String, SubmitLichessMoveResult]("lichess_token_expired"))
        else
          logMoveFailed(resp.status, gameId, move, raw).as(Left("lichess_move_failed"))
      }
    }.handleErrorWith(_ => IO.pure(Left("lichess_move_failed")))

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

  private def parseGameStateResponse(
      raw: String,
      requestedGameId: String,
      link: ExternalAccountLink
  ): Either[String, LichessGameStateResult] =
    try
      val json       = ujson.read(raw)
      val gameId     = json.obj.get("id").flatMap(_.strOpt).getOrElse(requestedGameId)
      val status     = json.obj.get("status").flatMap(_.strOpt).getOrElse("unknown")
      val fen        = json.obj.get("fen").flatMap(_.strOpt).filter(_.trim.nonEmpty)
      val moves      = json.obj.get("moves").flatMap(_.strOpt).getOrElse("")
      val whiteName  = playerUsername(json, "white").getOrElse("White")
      val blackName  = playerUsername(json, "black").getOrElse("Black")
      val userColor  = colorForUsername(link.externalUsername, whiteName, blackName)
      val botColor   = colorForUsername(config.botUsername, whiteName, blackName)
      val sideToMove = sideToMoveFrom(fen, moves)

      Right(LichessGameStateResult(
        gameId        = gameId,
        status        = status,
        fen           = fen,
        moves         = moves.trim,
        white         = LichessGamePlayerState(whiteName, isBotUsername(whiteName)),
        black         = LichessGamePlayerState(blackName, isBotUsername(blackName)),
        sideToMove    = sideToMove,
        userColor     = userColor,
        botColor      = botColor,
        url           = s"https://lichess.org/$gameId",
        lastUpdatedAt = java.time.Instant.now()
      ))
    catch
      case _: Exception => Left("lichess_game_state_failed")

  private def playerUsername(json: ujson.Value, color: String): Option[String] =
    json.obj.get("players").flatMap { players =>
      players.obj.get(color).flatMap { player =>
        player.obj.get("user").flatMap { user =>
          user.obj.get("name").flatMap(_.strOpt)
            .orElse(user.obj.get("id").flatMap(_.strOpt))
        }.orElse(player.obj.get("name").flatMap(_.strOpt))
      }
    }

  private def colorForUsername(username: String, whiteName: String, blackName: String): Option[String] =
    if whiteName.equalsIgnoreCase(username) then Some("white")
    else if blackName.equalsIgnoreCase(username) then Some("black")
    else None

  private def isBotUsername(username: String): Boolean =
    username.equalsIgnoreCase(config.botUsername)

  private def sideToMoveFrom(fen: Option[String], moves: String): String =
    fen.flatMap { value =>
      value.split("\\s+").drop(1).headOption.collect {
        case "w" => "white"
        case "b" => "black"
      }
    }.getOrElse {
      val plyCount = moves.split("\\s+").count(_.nonEmpty)
      if plyCount % 2 == 0 then "white" else "black"
    }

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

  private def logGameStateFailed(status: Status, gameId: String, raw: String): IO[Unit] =
    IO(StructuredLog.warn(
      "user-service",
      "lichess_game_state_failed",
      "status"          -> status.code,
      "gameId"          -> gameId,
      "targetBot"       -> config.botUsername,
      "responsePreview" -> sanitizedPreview(raw)
    ))

  private def logGameStateParseFailed(status: Status, gameId: String): IO[Unit] =
    IO(StructuredLog.warn(
      "user-service",
      "lichess_game_state_response_parse_failed",
      "status"    -> status.code,
      "gameId"    -> gameId,
      "targetBot" -> config.botUsername
    ))

  private def logMoveFailed(status: Status, gameId: String, move: String, raw: String): IO[Unit] =
    IO(StructuredLog.warn(
      "user-service",
      "lichess_move_failed",
      "status"          -> status.code,
      "gameId"          -> gameId,
      "move"            -> move,
      "responsePreview" -> sanitizedPreview(raw)
    ))

  private def sanitizedPreview(raw: String): String =
    raw.replaceAll("\\s+", " ").trim.take(300)

  private def validateGameId(gameId: String): Either[String, Unit] =
    if gameId.matches("[A-Za-z0-9_-]{4,32}") then Right(())
    else Left("gameId must be 4-32 URL-safe characters")

  private def validateMove(move: String): Either[String, String] =
    val normalized = move.trim.toLowerCase
    if normalized.matches("^[a-h][1-8][a-h][1-8][qrbn]?$") then Right(normalized)
    else Left("move must be UCI format")

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
