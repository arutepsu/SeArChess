package chess.lichessbot

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

/** Lightweight WebSocket Server specifically for the Lichess Chess Bot.
  * Pushes live game state changes to connected Web-UI clients.
  *
  * @param port The TCP port to listen on.
  * @param onClientConnect A callback function executed when a new client connects (e.g. to send the initial state).
  */
class LichessBotWebSocketServer(port: Int, onClientConnect: WebSocket => Unit)
    extends WebSocketServer(new InetSocketAddress(port)) {

  override def onOpen(conn: WebSocket, handshake: ClientHandshake): Unit = {
    println(s"[WebSocket] Client connected: ${conn.getRemoteSocketAddress}")
    onClientConnect(conn)
  }

  override def onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean): Unit = {
    println(s"[WebSocket] Client disconnected: ${conn.getRemoteSocketAddress}")
  }

  override def onMessage(conn: WebSocket, message: String): Unit = {
    // This server is outbound-only, ignore incoming client messages.
  }

  override def onError(conn: WebSocket, ex: Exception): Unit = {
    val address = Option(conn).flatMap(c => Option(c.getRemoteSocketAddress)).map(_.toString).getOrElse("unknown")
    println(s"[WebSocket] Error on connection $address: ${ex.getMessage}")
  }

  override def onStart(): Unit = {
    println(s"[WebSocket] Server started on port $port")
  }

  /** Broadcasts a message to all active connected clients.
    */
  override def broadcast(message: String): Unit = {
    super.broadcast(message)
  }
}
