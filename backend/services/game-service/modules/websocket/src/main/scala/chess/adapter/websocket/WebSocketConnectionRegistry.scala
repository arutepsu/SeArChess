package chess.adapter.websocket

import chess.application.session.model.SessionIds.GameId
import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArraySet}
import scala.jdk.CollectionConverters.*

/** Thread-safe in-memory registry that maps [[GameId]] subscriptions to live
  * [[WebSocketConnection]]s.
  *
  * Clients subscribe by connecting to a path that encodes their `gameId`. Multiple clients can
  * subscribe to the same `gameId`; all receive matching events.
  *
  * ===Thread safety===
  * [[ConcurrentHashMap]] and [[CopyOnWriteArraySet]] provide safe concurrent access across multiple
  * WebSocket connection threads without external synchronisation.
  *
  * ===Lifecycle===
  * Call [[subscribe]] on connection open and [[unsubscribe]] on connection close. The registry
  * never holds a reference to a connection longer than needed; stale entries are cleaned up on
  * [[unsubscribe]].
  */
class WebSocketConnectionRegistry:

  private val subscriptions: ConcurrentHashMap[GameId, CopyOnWriteArraySet[WebSocketConnection]] =
    ConcurrentHashMap()

  /** Register `conn` as a live subscriber for events on `gameId`. */
  def subscribe(conn: WebSocketConnection, gameId: GameId): Unit =
    subscriptions
      .computeIfAbsent(gameId, _ => CopyOnWriteArraySet())
      .add(conn)

  /** Remove `conn` from all game subscriptions.
    *
    * Safe to call even if `conn` was never registered.
    */
  def unsubscribe(conn: WebSocketConnection): Unit =
    subscriptions.values.forEach(_.remove(conn))

  /** All connections currently subscribed to events for `gameId`. */
  def subscribersFor(gameId: GameId): Set[WebSocketConnection] =
    Option(subscriptions.get(gameId))
      .map(_.asScala.toSet)
      .getOrElse(Set.empty)
