package chess.tournamentservice

import cats.effect.IO
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.control.NonFatal

final case class RunnerTournamentDetail(status: String, round: Int)

final case class RunnerRoundGame(
  gameId:     String,
  whiteBotId: String,
  blackBotId: String,
  status:     String
)

final case class RunnerGameSnapshot(
  fen:    Option[String],
  status: String
)

trait TournamentRunnerServerClient:
  def fetchTournamentDetail(tournamentId: String): IO[Either[String, RunnerTournamentDetail]]
  def fetchRoundPairings(tournamentId: String, round: Int): IO[Either[String, List[RunnerRoundGame]]]
  def fetchGameSnapshot(tournamentId: String, gameId: String): IO[Either[String, RunnerGameSnapshot]]

final class JdkTournamentRunnerServerClient(
    baseUrl:    String,
    underlying: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
) extends TournamentRunnerServerClient:

  private val base = baseUrl.stripSuffix("/")

  def fetchTournamentDetail(tournamentId: String): IO[Either[String, RunnerTournamentDetail]] =
    get(s"$base/api/tournament/$tournamentId").map(_.flatMap { body =>
      try
        val json   = ujson.read(body)
        val status = json.obj.get("status").flatMap(_.strOpt).getOrElse("unknown")
        val round  = json.obj.get("round").flatMap(_.numOpt).map(_.toInt).getOrElse(1)
        Right(RunnerTournamentDetail(status, round))
      catch case NonFatal(e) => Left(s"Failed to parse tournament detail: ${Option(e.getMessage).getOrElse("parse error")}")
    })

  def fetchRoundPairings(tournamentId: String, round: Int): IO[Either[String, List[RunnerRoundGame]]] =
    get(s"$base/api/tournament/$tournamentId/round/$round").map(_.flatMap { body =>
      try
        val json = ujson.read(body)
        // Tournament-server response: { round: N, pairings: [{ white, black, matches: [{gameId, whiteId}] }] }
        // Game status is not included in pairings; "ongoing" is the safe default since games in
        // the active round are either ongoing or not yet started. Finished games are caught by
        // PublicBotMoveGenerator which checks the live snapshot status before generating a move.
        val games = json.obj.get("pairings").toList.flatMap { arr =>
          arr.arr.toList.flatMap { pairing =>
            val whiteId = pairing.obj.get("white").flatMap(_.obj.get("id")).flatMap(_.strOpt)
            val blackId = pairing.obj.get("black").flatMap(_.obj.get("id")).flatMap(_.strOpt)
            pairing.obj.get("matches").toList.flatMap { matches =>
              matches.arr.toList.flatMap { m =>
                for
                  gameId <- m.obj.get("gameId").flatMap(_.strOpt)
                  white  <- whiteId
                  black  <- blackId
                yield RunnerRoundGame(gameId, white, black, "ongoing")
              }
            }
          }
        }
        Right(games)
      catch case NonFatal(e) => Left(s"Failed to parse round pairings: ${Option(e.getMessage).getOrElse("parse error")}")
    })

  def fetchGameSnapshot(tournamentId: String, gameId: String): IO[Either[String, RunnerGameSnapshot]] =
    get(s"$base/api/tournament/$tournamentId/game/$gameId").map(_.flatMap { body =>
      try
        val json   = ujson.read(body)
        val fen    = json.obj.get("fen").flatMap(_.strOpt).filter(_.trim.nonEmpty)
        val status = json.obj.get("status").flatMap(_.strOpt).getOrElse("unknown")
        Right(RunnerGameSnapshot(fen, status))
      catch case NonFatal(e) => Left(s"Failed to parse game snapshot: ${Option(e.getMessage).getOrElse("parse error")}")
    })

  private def get(url: String): IO[Either[String, String]] =
    IO.blocking {
      val req = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("Accept", "application/json")
        .GET()
        .build()
      underlying.send(req, HttpResponse.BodyHandlers.ofString())
    }.map { resp =>
      resp.statusCode() match
        case 200 => Right(resp.body())
        case n   => Left(s"HTTP $n from $url: ${resp.body().take(200)}")
    }.handleError:
      case NonFatal(e) =>
        Left(s"Network error fetching $url: ${Option(e.getMessage).getOrElse("unknown")}")
