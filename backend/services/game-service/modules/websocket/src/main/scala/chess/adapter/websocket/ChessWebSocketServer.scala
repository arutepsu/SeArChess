package chess.adapter.websocket

import chess.application.session.model.SessionIds.GameId
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.UUID

/** Embeddable WebSocket server for outbound live game updates.
  *
  * ===Subscription model===
  * Clients connect to `/ws/games/{gameId}` where `{gameId}` is the UUID from the REST API. On
  * successful connection the client is registered in the shared [[WebSocketConnectionRegistry]] and
  * will receive every [[chess.application.event.AppEvent]] published for that game as a JSON
  * message.
  *
  * Connections with an unrecognised or malformed path are closed with code 1008 (policy violation)
  * immediately after the handshake.
  *
  * ===Protocol===
  * Outbound only — incoming client messages are silently ignored. No authentication, no reconnect
  * recovery, no history replay.
  *
  * ===Wiring===
  * The same [[WebSocketConnectionRegistry]] instance must be passed to both this server and the
  * [[WebSocketEventPublisher]] so that connections registered here receive messages forwarded by
  * the publisher.
  *
  * ===Coverage exclusion===
  * This class requires live network connections and is excluded from the sbt-scoverage report. The
  * testable adapter logic lives in [[WebSocketEventPublisher]], [[WebSocketConnectionRegistry]],
  * and [[WebSocketMessageMapper]].
  *
  * @param port
  *   TCP port to listen on; pass 0 for an ephemeral port
  * @param registry
  *   connection registry shared with [[WebSocketEventPublisher]]
  */
class ChessWebSocketServer(port: Int, registry: WebSocketConnectionRegistry)
    extends WebSocketServer(InetSocketAddress(port)):

  override def onOpen(conn: WebSocket, handshake: ClientHandshake): Unit =
    parseGameId(handshake.getResourceDescriptor) match
      case Some(gameId) => registry.subscribe(JavaWebSocketConnection(conn), gameId)
      case None         => conn.close(1008, s"Unknown path: ${handshake.getResourceDescriptor}")

  override def onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean): Unit =
    registry.unsubscribe(JavaWebSocketConnection(conn))

  /** Incoming messages are not processed — this server is outbound-only. */
  override def onMessage(conn: WebSocket, message: String): Unit = ()

  override def onError(conn: WebSocket, ex: Exception): Unit = ()

  override def onStart(): Unit = ()

  // ── path parsing ─────────────────────────────────────────────────────────

  /** Parse `/ws/games/{uuid}` → `Some(GameId)`.  Anything else → `None`. */
  private def parseGameId(path: String): Option[GameId] =
    path.split("/").filter(_.nonEmpty) match
      case Array("ws", "games", id) =>
        try Some(GameId(UUID.fromString(id)))
        catch case _: IllegalArgumentException => None
      case _ => None
