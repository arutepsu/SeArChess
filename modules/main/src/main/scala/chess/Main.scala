package chess

import cats.effect.unsafe.implicits.global
import chess.adapter.event.FanOutEventPublisher
import chess.adapter.gui.ChessApp
import chess.adapter.http4s.Http4sServer
import chess.adapter.repository.{InMemoryGameRepository, InMemorySessionGameStore, InMemorySessionRepository}
import chess.adapter.textui.TuiRunner
import chess.adapter.websocket.{ChessWebSocketServer, WebSocketConnectionRegistry, WebSocketEventPublisher}
import chess.application.ObservableGame
import chess.application.session.model.{DesktopSessionContext, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.application.session.service.{SessionGameService, SessionService}
import scalafx.application.Platform

object Main:

  def main(args: Array[String]): Unit =

    // ── Shared persistence ─────────────────────────────────────────────────
    val sessionRepo = InMemorySessionRepository()
    val gameRepo    = InMemoryGameRepository()

    // ── WebSocket push bridge ──────────────────────────────────────────────
    // Registry is shared: ChessWebSocketServer registers/unregisters live
    // connections; WebSocketEventPublisher routes AppEvents to those connections.
    val wsRegistry  = WebSocketConnectionRegistry()
    val wsPublisher = WebSocketEventPublisher(wsRegistry)
    val wsServer    = ChessWebSocketServer(port = 9090, wsRegistry)
    wsServer.start()

    // ── Application service layer ──────────────────────────────────────────
    // FanOutEventPublisher forwards each AppEvent to every wired publisher.
    // Add further publishers here (e.g. structured logging) without touching
    // the application layer.
    // fanOut is shared: SessionService uses it for createSession/preparePromotion
    // events; SessionGameService uses it for move-related events published after
    // the combined session+game-state write completes.
    val fanOut             = FanOutEventPublisher(wsPublisher)
    val sessionService     = SessionService(sessionRepo, fanOut)
    val store              = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionGameService = SessionGameService(sessionService, store, fanOut)

    // ── REST server ────────────────────────────────────────────────────────
    // Allocated as a Cats Effect Resource; the IO[Unit] shuts the server down.
    val (_, shutdownHttp) = Http4sServer(sessionGameService, gameRepo, port = 8080)
      .resource.allocated.unsafeRunSync()

    // ── One shared desktop session ─────────────────────────────────────────
    // GUI and TUI are two input/rendering adapters over ONE authoritative
    // game identity.  A single session is created here at startup and injected
    // into both adapters.  Moves from either adapter go through the same
    // sessionGameService → same sessionRepo → same gameRepo → same gameId.
    // ObservableGame is updated after each move as a notification bridge only.
    val desktopSession = sessionGameService
      .createSession(GameId.random(), SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal)
      .fold(err => throw RuntimeException(s"[Desktop] Failed to create session: $err"), identity)
    val desktopContext = new DesktopSessionContext(desktopSession)

    // ── Desktop GUI + TUI ──────────────────────────────────────────────────
    val game = new ObservableGame()
    ChessApp.prepareWith(game)
    ChessApp.prepareSessionGame(sessionGameService, desktopContext)
    ChessApp.prepareAfterStart(() =>
      TuiRunner.start(game, sessionGameService, desktopContext, onUserQuit = () => {
        shutdownHttp.unsafeRunSync()
        wsServer.stop(0)
        Platform.runLater { Platform.exit() }
      })
    )
    ChessApp.main(args)
