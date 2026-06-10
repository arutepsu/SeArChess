package chess.lichessbridge

import cats.effect.{IO, Ref}
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`  // used for JSON responses
import org.typelevel.ci.CIString

/** HTTP routes for the Lichess Bridge service.
  *
  * Phase 2B-2 surface (extends 2B-1):
  *   GET  /health                              — liveness probe
  *   GET  /internal/lichess/status             — bridge status + worker state (token never exposed)
  *   GET  /internal/lichess/validate           — token + profile check against Lichess API
  *   POST /internal/lichess/challenge-ai/spike — create a real AI challenge game on Lichess
  *   GET  /internal/lichess/policy             — current challenge policy config (no secrets)
  *   GET  /internal/lichess/games/{id}/events  — SSE stream of live bot game snapshots
  *
  * Phase 2B-2 status additions: activeGamesCount, per-game side, lastMoveCount,
  * lastSubmittedMove, lastGameEventAt. Token is never present in any response.
  */
class LichessBridgeRoutes(
    config: LichessBridgeConfig,
    client: LichessClient[IO],
    stateRef: Ref[IO, WorkerState],
    snapshotHub: BotGameSnapshotHub
):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "health" =>
      json(
        Status.Ok,
        ujson.Obj(
          "status"  -> "ok",
          "service" -> "lichess-bridge-service"
        )
      )

    case GET -> Root / "internal" / "lichess" / "status" =>
      stateRef.get.flatMap { state =>
        val activeList = ujson.Arr(
          state.activeGames.values.toSeq.sortBy(_.startedAt.toString).map { g =>
            val obj = ujson.Obj(
              "gameId"    -> g.gameId,
              "opponent"  -> g.opponent,
              "startedAt" -> g.startedAt.toString
            )
            g.side.foreach(s => obj("side") = s)
            g.lastMoveCount.foreach(c => obj("lastMoveCount") = c)
            g.lastSubmittedMove.foreach(m => obj("lastSubmittedMove") = m)
            g.lastGameEventAt.foreach(t => obj("lastGameEventAt") = t.toString)
            obj
          }*
        )
        json(
          Status.Ok,
          ujson.Obj(
            "service"               -> "lichess-bridge-service",
            "phase"                 -> "2B-2",
            "enabled"               -> config.enabled,
            "lichessApiBaseUrl"     -> config.lichessApiBaseUrl,
            "botUsernameConfigured" -> config.botUsernameConfigured,
            "tokenConfigured"       -> config.tokenConfigured,
            "maxConcurrentGames"    -> config.maxConcurrentGames,
            "aiServiceUrl"          -> config.aiServiceUrl,
            "workerRunning"         -> state.running,
            "acceptChallenges"      -> config.acceptChallenges,
            "activeGames"           -> activeList,
            "activeGamesCount"      -> state.activeGames.size,
            "lastEventAt"           -> state.lastEventAt.fold(ujson.Null)(t => ujson.Str(t.toString)),
            "lastError"             -> state.lastError.fold(ujson.Null)(ujson.Str(_))
          )
        )
      }

    case GET -> Root / "internal" / "lichess" / "validate" =>
      if !config.enabled && !config.tokenConfigured then
        json(
          Status.Ok,
          ujson.Obj(
            "status"  -> "disabled_not_configured",
            "message" -> "Bridge disabled and no token configured."
          )
        )
      else
        config.lichessBotToken match
          case None =>
            json(
              Status.Ok,
              ujson.Obj(
                "status"  -> "not_configured",
                "message" -> "No Lichess token configured."
              )
            )
          case Some(token) =>
            client.getBotProfile(token).flatMap(mapProfileResult)

    case POST -> Root / "internal" / "lichess" / "challenge-ai" / "spike" =>
      if !config.enabled then
        json(Status.Forbidden, ujson.Obj("status" -> "bridge_disabled"))
      else if !config.tokenConfigured then
        json(Status.Forbidden, ujson.Obj("status" -> "no_token"))
      else if !config.botUsernameConfigured then
        json(Status.Forbidden, ujson.Obj("status" -> "no_bot_username"))
      else
        config.lichessBotToken match
          case None =>
            json(Status.Forbidden, ujson.Obj("status" -> "no_token"))
          case Some(token) =>
            client.challengeAi(token).flatMap(mapChallengeResult)

    case GET -> Root / "internal" / "lichess" / "policy" =>
      val challengers =
        if config.allowedChallengers.isEmpty then ujson.Null
        else ujson.Arr(config.allowedChallengers.toSeq.sorted.map(ujson.Str(_))*)
      json(
        Status.Ok,
        ujson.Obj(
          "acceptChallenges"    -> config.acceptChallenges,
          "acceptRated"         -> config.acceptRated,
          "allowedVariants"     -> ujson.Arr(config.allowedVariants.toSeq.sorted.map(ujson.Str(_))*),
          "minClockSeconds"     -> config.minClockSeconds,
          "maxClockSeconds"     -> config.maxClockSeconds,
          "maxConcurrentGames"  -> config.maxConcurrentGames,
          "allowedChallengers"  -> challengers
        )
      )

    case GET -> Root / "internal" / "lichess" / "games" / gameId / "events" =>
      val snapStream: Stream[IO, BotGameSnapshot] =
        Stream.eval(snapshotHub.latest(gameId)).flatMap {
          case Some(snap) if isTerminalStatus(snap.status) =>
            Stream.emit(snap)
          case Some(snap) =>
            Stream.emit(snap) ++
              snapshotHub.subscribe(gameId)
                .filter(_.lastUpdated > snap.lastUpdated)
                .takeThrough(s => !isTerminalStatus(s.status))
          case None =>
            snapshotHub.subscribe(gameId)
              .takeThrough(s => !isTerminalStatus(s.status))
        }
      val sseBody: Stream[IO, Byte] = snapStream
        .map(snap => s"data: ${ujson.write(BotGameSnapshot.toJson(snap))}\n\n")
        .through(fs2.text.utf8.encode)
      IO.pure(
        Response[IO](
          status  = Status.Ok,
          headers = Headers(
            Header.Raw(CIString("Content-Type"),      "text/event-stream"),
            Header.Raw(CIString("Cache-Control"),     "no-cache"),
            Header.Raw(CIString("X-Accel-Buffering"), "no")
          ),
          body = sseBody
        )
      )
  }

  // ── Error mapping helpers ───────────────────────────────────────────────────

  private def mapProfileResult(result: Either[LichessError, BotProfile]): IO[Response[IO]] =
    result match
      case Right(profile) => renderProfile(profile)
      case Left(err)      => mapError(err)

  private def mapChallengeResult(result: Either[LichessError, ChallengeResult]): IO[Response[IO]] =
    result match
      case Right(challenge) => renderChallenge(challenge)
      case Left(err)        => mapError(err)

  private def renderProfile(profile: BotProfile): IO[Response[IO]] =
    val profileObj = ujson.Obj(
      "id"       -> profile.id,
      "username" -> profile.username,
      "isBot"    -> profile.isBot
    )
    profile.title.foreach(t => profileObj("title") = t)
    json(Status.Ok, ujson.Obj("status" -> "ok", "botProfile" -> profileObj))

  private def renderChallenge(challenge: ChallengeResult): IO[Response[IO]] =
    json(
      Status.Ok,
      ujson.Obj(
        "status"  -> "created",
        "gameId"  -> challenge.gameId,
        "gameUrl" -> challenge.gameUrl,
        "note"    -> "This is a real game on Lichess."
      )
    )

  private def mapError(err: LichessError): IO[Response[IO]] =
    err match
      case LichessError.Unauthorized(_) =>
        json(Status.Unauthorized, ujson.Obj("status" -> "invalid_token", "message" -> "Token rejected by Lichess."))
      case LichessError.RateLimited(retryAfter) =>
        val msg = retryAfter.fold("Lichess rate limit hit.")(s => s"Lichess rate limit hit. Retry after $s seconds.")
        json(Status.TooManyRequests, ujson.Obj("status" -> "rate_limited", "message" -> msg))
      case LichessError.NetworkError(msg) =>
        json(Status.ServiceUnavailable, ujson.Obj("status" -> "network_error", "message" -> msg))
      case LichessError.UnexpectedResponse(httpStatus, _) =>
        json(Status.BadGateway, ujson.Obj("status" -> "upstream_error", "message" -> s"Unexpected response $httpStatus from Lichess."))

  private def json(status: Status, body: ujson.Value): IO[Response[IO]] =
    IO.pure(
      Response[IO](
        status  = status,
        headers = Headers(`Content-Type`(MediaType.application.json)),
        body    = Stream.emits(ujson.write(body).getBytes("UTF-8")).covary[IO]
      )
    )

  private val terminalStatuses: Set[String] =
    Set("aborted", "mate", "resign", "stalemate", "timeout", "draw", "outoftime", "cheat", "nostart", "unknownfinish", "variantend")

  private def isTerminalStatus(status: String): Boolean =
    val s = status.toLowerCase
    terminalStatuses.contains(s) || s.contains("finish")
