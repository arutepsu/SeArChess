package chess.server

import cats.effect.IO
import chess.adapter.websocket.ChessWebSocketServer

/** Live server runtime handles produced by [[ServerWiring.start]].
 *
 *  Contains only the server-specific concerns started during server startup.
 *  Does not carry stable application services — those live in
 *  [[chess.startup.assembly.AppContext]].
 *
 *  @param wsServer      live WebSocket server handle, present when WebSocket is
 *                       enabled in config; call `stop(0)` on the contained
 *                       value to shut down
 *  @param shutdownHttp  effect that cleanly terminates the HTTP server
 *  @param shutdownEvents effect that stops background event infrastructure
 */
final case class ServerRuntime(
  wsServer:       Option[ChessWebSocketServer],
  shutdownHttp:   IO[Unit],
  shutdownEvents: IO[Unit] = IO.unit
)
