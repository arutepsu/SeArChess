package chess.lichessbridge

import cats.effect.IO

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.control.NonFatal

// ── Authorization result ───────────────────────────────────────────────────────

enum ChallengeAuthResult:
  case Authorized
  case NotLinked
  case Unavailable

// ── Client trait ──────────────────────────────────────────────────────────────

trait UserServiceClient[F[_]]:
  /** Ask user-service whether the given Lichess username is linked to an allowed Searchess user.
    * Fails closed: returns Unavailable on any network or parse error.
    */
  def authorizeChallenger(lichessUsername: String): F[ChallengeAuthResult]

// ── JDK HTTP implementation ───────────────────────────────────────────────────

final class JdkUserServiceClient(
    baseUrl: String,
    apiKey: String,
    underlying: HttpClient = HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build()
) extends UserServiceClient[IO]:

  private val requestTimeout = Duration.ofSeconds(5)

  def authorizeChallenger(lichessUsername: String): IO[ChallengeAuthResult] =
    IO.blocking {
      val req = HttpRequest
        .newBuilder(URI.create(s"$baseUrl/internal/lichess/challenge-auth/$lichessUsername"))
        .timeout(requestTimeout)
        .header("X-Internal-Api-Key", apiKey)
        .header("Accept", "application/json")
        .GET()
        .build()
      val resp = underlying.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() == 200 then
        JdkUserServiceClient.parseAuthResponse(resp.body())
      else
        ChallengeAuthResult.Unavailable
    }.handleErrorWith { case NonFatal(_) =>
      IO.pure(ChallengeAuthResult.Unavailable)
    }

object JdkUserServiceClient:

  def parseAuthResponse(body: String): ChallengeAuthResult =
    scala.util.Try {
      val json = ujson.read(body)
      if json("allowed").bool then ChallengeAuthResult.Authorized
      else ChallengeAuthResult.NotLinked
    }.getOrElse(ChallengeAuthResult.Unavailable)
