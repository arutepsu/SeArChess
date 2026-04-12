package chess

import cats.effect.unsafe.implicits.global
import chess.adapter.event.FanOutEventPublisher
import chess.adapter.gui.ChessApp
import chess.adapter.http4s.Http4sServer
import chess.adapter.repository.{InMemoryGameRepository, InMemorySessionRepository}
import chess.adapter.textui.TuiRunner
import chess.adapter.websocket.{ChessWebSocketServer, WebSocketConnectionRegistry, WebSocketEventPublisher}
import chess.application.ObservableGame
import chess.application.session.service.SessionService
import scalafx.application.Platform

object Main:

  def main(args: Array[String]): Unit =

    // ── Shared persistence (REST path) ─────────────────────────────────────
    val sessionRepo = InMemorySessionRepository()
    val gameRepo    = InMemoryGameRepository()

    // ── WebSocket push bridge ──────────────────────────────────────────────
    // Registry is shared: ChessWebSocketServer registers/unregisters live
    // connections; WebSocketEventPublisher routes AppEvents to those connections.
    val wsRegistry  = WebSocketConnectionRegistry()
    val wsPublisher = WebSocketEventPublisher(wsRegistry)
    val wsServer    = ChessWebSocketServer(port = 9090, wsRegistry)
    wsServer.start()

    // ── Application service for the REST path ──────────────────────────────
    // FanOutEventPublisher forwards each AppEvent to every wired publisher.
    // Add further publishers here (e.g. structured logging) without touching
    // the application layer.
    val sessionService = SessionService(sessionRepo, FanOutEventPublisher(wsPublisher))

    // ── REST server ────────────────────────────────────────────────────────
    // Allocated as a Cats Effect Resource; the IO[Unit] shuts the server down.
    val (_, shutdownHttp) = Http4sServer(sessionService, gameRepo, port = 8080)
      .resource.allocated.unsafeRunSync()

    // ── Desktop GUI + TUI ──────────────────────────────────────────────────
    // GUI-driven moves now flow through SessionGameService (the unified
    // application mutation boundary).  The same wsPublisher is shared with
    // the REST path so web clients receive events from both adapters.
    val game = new ObservableGame()
    ChessApp.prepareWith(game)
    ChessApp.preparePublisher(wsPublisher)
    ChessApp.prepareAfterStart(() =>
      TuiRunner.start(game, onUserQuit = () => {
        shutdownHttp.unsafeRunSync()
        wsServer.stop(0)
        Platform.runLater { Platform.exit() }
      })
    )
    ChessApp.main(args)
