package chess.adapter.http4s.route

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable

/** Per-user rate limiter for bot game session creation.
  *
  * Keyed on the authenticated Searchess [[userId]], not on IP or nickname.
  * Reason: Envoy and load-balancer IPs are not reliable identity tokens; the
  * Keycloak [[userId]] is the correct application-level identity.
  *
  * Implementations must be thread-safe.
  *
  * ===Production note===
  * [[InMemoryBotSessionRateLimiter]] is correct for a single-replica deployment.
  * For multi-replica production use, replace with a Redis-backed sliding-window
  * implementation (ZRANGEBYSCORE + ZADD + ZREMRANGEBYSCORE in a Lua script, or
  * the equivalent atomic pipeline).
  */
trait BotSessionRateLimiter:

  /** Atomically check whether [[userId]] is within the rate limit and, if so,
    * record the current request.
    *
    * The check and record must happen together — a separate
    * "check then record" sequence would admit races.
    *
    * @return
    *   `Right(())` — request is allowed (and has been recorded).
    *   `Left(RateLimited)` — user has exceeded the limit; request must be
    *   rejected with 429.
    */
  def checkAndRecord(userId: UUID): Either[BotSessionRateLimiter.RateLimited.type, Unit]

object BotSessionRateLimiter:
  case object RateLimited

/** Sliding-window in-memory rate limiter.
  *
  * Stores a per-user deque of [[Instant]]s. On each call, entries older than
  * [[windowSeconds]] are pruned and the remaining count is compared against
  * [[limitPerWindow]].
  *
  * Thread-safety: the deque for each user is locked with `synchronized` before
  * mutation. Different users do not contend.
  *
  * Acceptable for a single-replica game-service deployment.
  * Controlled by env vars [[BOT_GAME_CREATE_LIMIT_PER_MINUTE]] and
  * [[BOT_GAME_CREATE_RATE_WINDOW_SECONDS]].
  *
  * @param limitPerWindow max requests allowed per user within the window
  * @param windowSeconds  width of the sliding window in seconds
  */
final class InMemoryBotSessionRateLimiter(
    val limitPerWindow: Int,
    val windowSeconds: Int
) extends BotSessionRateLimiter:

  private val buckets: ConcurrentHashMap[UUID, mutable.ArrayDeque[Instant]] =
    new ConcurrentHashMap()

  def checkAndRecord(userId: UUID): Either[BotSessionRateLimiter.RateLimited.type, Unit] =
    val now = Instant.now()
    val cutoff = now.minusSeconds(windowSeconds.toLong)
    val deque = buckets.computeIfAbsent(userId, _ => new mutable.ArrayDeque[Instant]())
    deque.synchronized {
      while deque.headOption.exists(_.isBefore(cutoff)) do deque.removeHead()
      if deque.size >= limitPerWindow then
        Left(BotSessionRateLimiter.RateLimited)
      else
        deque.addOne(now)
        Right(())
    }
