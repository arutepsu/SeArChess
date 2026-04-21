package chess.adapter.websocket

/** Minimal abstraction over an outbound WebSocket connection.
  *
  * Separates the connection registry and event publisher from the concrete Java-WebSocket library
  * so that tests can supply stub implementations without a real network connection.
  *
  * ===Implementations===
  *   - [[JavaWebSocketConnection]] wraps a live `org.java_websocket.WebSocket`
  *   - Test stubs implement this trait inline
  *
  * ===Contract===
  * `send` must not throw — implementations should absorb transient errors and the publisher wraps
  * calls in a try-catch anyway.
  */
trait WebSocketConnection:

  /** Stable identifier for this connection; used as the registry key. */
  def id: String

  /** Push `message` to the connected client. */
  def send(message: String): Unit
