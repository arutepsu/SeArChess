package chess.gatewayservice

import java.util.concurrent.ConcurrentHashMap

/** In-memory cache: Keycloak subject → tournament-server (userId, JWT).
  *
  * Thread-safe. The token is never returned to frontend callers — it stays
  * server-side within the gateway process.
  */
final class TournamentJwtCache:

  private val entries = new ConcurrentHashMap[String, TournamentJwtCache.Entry]()

  def get(sub: String): Option[TournamentJwtCache.Entry] =
    Option(entries.get(sub))

  def put(sub: String, entry: TournamentJwtCache.Entry): Unit =
    entries.put(sub, entry)

  def remove(sub: String): Unit =
    entries.remove(sub)

object TournamentJwtCache:
  final case class Entry(tournamentUserId: String, token: String)
