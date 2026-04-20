package chess.adapter.websocket

import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher

/** [[EventPublisher]] adapter that routes [[AppEvent]]s to WebSocket clients.
 *
 *  On each [[publish]] call:
 *  1. The event is mapped to a JSON string by [[WebSocketMessageMapper]].
 *  2. All connections subscribed to `event.gameId` in the [[WebSocketConnectionRegistry]]
 *     receive the message.
 *
 *  Individual send failures are swallowed — a broken connection must not
 *  interrupt delivery to other subscribers or propagate an exception back to
 *  the service caller.
 *
 *  Connection management (subscribe / unsubscribe) is the responsibility of
 *  [[ChessWebSocketServer]]; this class only forwards messages.
 *
 *  @param registry live connection registry shared with [[ChessWebSocketServer]]
 */
class WebSocketEventPublisher(registry: WebSocketConnectionRegistry) extends EventPublisher:

  override def publish(event: AppEvent): Unit =
    val message     = WebSocketMessageMapper.toMessage(event)
    val subscribers = registry.subscribersFor(event.gameId)
    subscribers.foreach { conn =>
      try conn.send(message)
      catch case _: Exception => () // absorb per-connection failures
    }
