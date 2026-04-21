package chess.adapter.websocket

import org.java_websocket.WebSocket

/** [[WebSocketConnection]] backed by a live [[WebSocket]] from the Java-WebSocket library.
  *
  * Equality and hash code are based on the identity of the underlying [[WebSocket]] object so that
  * [[WebSocketConnectionRegistry.unsubscribe]] can locate and remove the correct entry when
  * wrapping the same connection in separate instances on open and close.
  *
  * This class is excluded from coverage because it requires a real network connection and the
  * Java-WebSocket interface has too many methods to stub cleanly. The adapter logic under test
  * lives in [[WebSocketEventPublisher]], [[WebSocketConnectionRegistry]], and
  * [[WebSocketMessageMapper]], all of which use the [[WebSocketConnection]] trait with simple
  * inline stubs.
  */
final class JavaWebSocketConnection(private val conn: WebSocket) extends WebSocketConnection:

  override val id: String =
    Option(conn.getRemoteSocketAddress)
      .map(_.toString)
      .getOrElse(System.identityHashCode(conn).toString)

  override def send(message: String): Unit = conn.send(message)

  override def equals(obj: Any): Boolean = obj match
    case other: JavaWebSocketConnection => this.conn eq other.conn
    case _                              => false

  override def hashCode: Int = System.identityHashCode(conn)
