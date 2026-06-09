package chess.lichessbridge

import cats.effect.IO
import fs2.Stream

import java.io.InputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.control.NonFatal

// ── Streaming exceptions ───────────────────────────────────────────────────────
// Raised into the stream; worker catches and applies reconnect logic.

sealed abstract class LichessStreamException(message: String) extends Exception(message)

final class LichessAuthException(message: String)
    extends LichessStreamException(message)

final class LichessRateLimitException(val retryAfterSeconds: Option[Int])
    extends LichessStreamException(
      retryAfterSeconds.fold("Rate limited by Lichess")(s => s"Rate limited by Lichess — retry after ${s}s")
    )

final class LichessNetworkException(message: String)
    extends LichessStreamException(message)

// ── Stream interfaces ──────────────────────────────────────────────────────────
// Separate from LichessClient because streaming uses different lifecycle semantics.

trait LichessEventStream[F[_]]:
  /** Persistent NDJSON stream from GET /api/stream/event.
    * Emits Right(event) for parseable lines; Left(error) for malformed lines.
    * Auth / network failures are raised into the stream as LichessStreamException subtypes.
    */
  def streamBotEvents(token: String): Stream[F, Either[ParseError, LichessBotEvent]]

trait LichessGameStream[F[_]]:
  /** Per-game NDJSON stream from GET /api/bot/game/stream/{gameId}.
    * Emits Right(event) for parseable lines; Left(error) for malformed lines.
    */
  def streamGame(token: String, gameId: String): Stream[F, Either[ParseError, LichessGameEvent]]

// ── JDK implementation ─────────────────────────────────────────────────────────
// Uses java.net.http.HttpClient with BodyHandlers.ofInputStream() + fs2.io.readInputStream.
// IO.blocking ensures the blocking send() uses the cats-effect blocking thread pool.

final class JdkLichessStreamClient(
    baseUrl: String,
    underlying: HttpClient = HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build()
) extends LichessEventStream[IO]
    with LichessGameStream[IO]:

  def streamBotEvents(token: String): Stream[IO, Either[ParseError, LichessBotEvent]] =
    openNdjsonStream(s"$baseUrl/api/stream/event", token)
      .map(LichessNdjsonParser.parseBotEventLine)

  def streamGame(token: String, gameId: String): Stream[IO, Either[ParseError, LichessGameEvent]] =
    openNdjsonStream(s"$baseUrl/api/bot/game/stream/$gameId", token)
      .map(LichessNdjsonParser.parseGameEventLine)

  // Opens an HTTP streaming response and emits each non-blank line as a String.
  // Raises LichessStreamException subtypes for auth / rate-limit / unexpected status codes.
  private def openNdjsonStream(url: String, token: String): Stream[IO, String] =
    Stream
      .eval(IO.blocking {
        val req = HttpRequest
          .newBuilder(URI.create(url))
          .header("Authorization", s"Bearer $token")
          .header("Accept", "application/x-ndjson")
          .GET()
          .build()
        underlying.send(req, HttpResponse.BodyHandlers.ofInputStream())
      })
      .flatMap { resp =>
        resp.statusCode() match
          case 200 =>
            fs2.io
              .readInputStream[IO](IO.pure(resp.body()), chunkSize = 4096, closeAfterUse = true)
              .through(fs2.text.utf8.decode)
              .through(fs2.text.lines)
              .filter(_.nonEmpty)
          case 401 | 403 =>
            Stream.raiseError[IO](LichessAuthException(s"HTTP ${resp.statusCode()} from Lichess stream $url"))
          case 429 =>
            Stream.raiseError[IO](LichessRateLimitException(retryAfterHeader(resp)))
          case status =>
            Stream.raiseError[IO](LichessNetworkException(s"Unexpected status $status from Lichess stream $url"))
      }
      .handleErrorWith {
        case e: LichessStreamException => Stream.raiseError[IO](e)
        case NonFatal(e)               => Stream.raiseError[IO](LichessNetworkException(e.getMessage))
      }

  private def retryAfterHeader(resp: HttpResponse[InputStream]): Option[Int] =
    val opt = resp.headers().firstValue("Retry-After")
    if opt.isPresent then opt.get().toIntOption else None
