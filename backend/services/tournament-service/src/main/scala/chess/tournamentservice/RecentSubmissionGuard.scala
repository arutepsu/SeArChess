package chess.tournamentservice

import java.util.concurrent.ConcurrentHashMap

// In-memory TTL guard that prevents re-submitting a move for the exact same
// position (tournamentId:gameId:botId:fenHash) within a configurable window.
//
// Two rapid scheduler ticks that see the same stale FEN before the external
// Tournament Server snapshot advances would otherwise both submit identical moves.
// The inFlight guard inside PublicTournamentBotRunner covers concurrent ticks;
// this guard covers sequential ticks across separate invocations.
//
// Not persisted: a process restart clears the guard.
// Multi-replica deployments will need a distributed cache (e.g. Redis) in Phase 4C.
//
// The nowMillis parameter is injectable for deterministic unit tests.
final class RecentSubmissionGuard(
    ttlMillis: Long,
    nowMillis: () => Long = () => System.currentTimeMillis()
):
  private val submitted = new ConcurrentHashMap[String, Long]()

  def isRecentlySubmitted(key: String): Boolean =
    val expiresAt = submitted.getOrDefault(key, 0L)
    nowMillis() < expiresAt

  def markSubmitted(key: String): Unit =
    val _ = submitted.put(key, nowMillis() + ttlMillis)
