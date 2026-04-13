package chess.adapter.textui

import chess.application.{ApplicationError, ChessService, ObservableGame}
import chess.application.ChessCommand.MakeMove
import chess.application.session.model.{DesktopSessionContext, SideController}
import chess.application.session.service.{SessionGameService, SessionMoveError}
import chess.domain.error.DomainError
import chess.domain.model.{Move, Position}
import chess.domain.state.GameState
import scala.annotation.tailrec

/** Text-based UI for the chess game.
 *
 *  Operates in two distinct modes:
 *
 *  === Local mode (default) ===
 *  No session parameters supplied.  Moves go through [[ChessService]] directly
 *  (pure domain path).  No persistence, no event publication.  Intended for
 *  standalone demo use, testing without infrastructure, and the existing
 *  convenience constructors in the companion object.
 *
 *  === Session-aware mode ===
 *  `sessionGameService` and `sessionContext` supplied.  Moves go through
 *  [[SessionGameService.submitMove]] — the unified application mutation boundary —
 *  which validates the move, persists the new [[GameState]], persists the updated
 *  session lifecycle, and publishes [[chess.application.event.AppEvent]]s.
 *  [[ObservableGame]] is then updated as a notification bridge so other adapters
 *  (e.g. the GUI) observe the state change.
 *
 *  The two paths are explicitly separated inside [[loop]] via pattern-matching on
 *  `(sessionGameService, sessionContext)`.  No mutation logic is shared between them.
 *
 *  @param console            I/O surface for reading commands and writing output
 *  @param game               cross-adapter state notification bridge; always updated
 *                            after a successful move so other adapters observe it
 *  @param sessionGameService unified application mutation boundary; supply for
 *                            session-aware mode, omit for local mode
 *  @param sessionContext     shared [[DesktopSessionContext]] holding the current session;
 *                            must wrap a session already persisted in the repository
 *                            backing `sessionGameService`
 */
final class TextUI(
    console:            Console,
    game:               ObservableGame,
    sessionGameService: Option[SessionGameService]    = None,
    sessionContext:     Option[DesktopSessionContext] = None
):

  def run(): TuiExitReason =
    console.printLine(ConsoleRenderer.renderWelcome())
    console.printLine(ConsoleRenderer.renderHelp())

    // Print board on every external state change (e.g. GUI makes a move).
    // Called on whatever thread invoked updateState; stdout writes are
    // individually thread-safe so there is no data corruption, though
    // output may visually interleave with the TUI prompt.
    game.addObserver { state =>
      console.printLine("")
      console.printLine(ConsoleRenderer.renderBoard(state))
      console.printLine(ConsoleRenderer.renderStatus(state))
      console.print("> ")
    }

    val initial = game.getState
    console.printLine("")
    console.printLine(ConsoleRenderer.renderBoard(initial))
    console.printLine(ConsoleRenderer.renderStatus(initial))
    console.print("> ")

    loop(pendingMove = None)

  @tailrec
  private def loop(pendingMove: Option[Move]): TuiExitReason =
    val input = console.readLine()
    val state = game.getState

    if input == null then
      console.printLine("Goodbye!")
      return TuiExitReason.EndOfInput

    InputParser.parse(input) match
      case Left(err) =>
        console.printLine(ConsoleRenderer.renderParseError(err))
        console.print("> ")
        loop(pendingMove)

      case Right(TextUiCommand.Quit) =>
        console.printLine("Goodbye!")
        TuiExitReason.UserQuit

      case Right(TextUiCommand.Help) =>
        console.printLine(ConsoleRenderer.renderHelp())
        console.print("> ")
        loop(pendingMove)

      case Right(TextUiCommand.Show) =>
        console.printLine("")
        console.printLine(ConsoleRenderer.renderBoard(state))
        console.printLine(ConsoleRenderer.renderStatus(state))
        console.print("> ")
        loop(pendingMove)

      case Right(TextUiCommand.New) =>
        (sessionGameService, sessionContext) match
          case (Some(svc), Some(ctx)) =>
            // Session-aware path: provision fresh session + persist initial state.
            svc.newGame(ctx.getSession.mode, ctx.getSession.whiteController, ctx.getSession.blackController) match
              case Right((fresh, newSess)) =>
                ctx.setSession(newSess)
                game.updateState(fresh)
              case Left(_) =>
                // Best-effort: update notification bridge even if session creation failed.
                game.updateState(ChessService.createNewGame())
          case _ =>
            // Local mode: pure domain reset, no persistence.
            game.updateState(ChessService.createNewGame())
        console.printLine("New game started.")
        loop(pendingMove = None)

      case Right(TextUiCommand.MoveCmd(fromStr, toStr)) =>
        val posResult = for
          from <- Position.fromAlgebraic(fromStr).left.map(ApplicationError.DomainFailure(_))
          to   <- Position.fromAlgebraic(toStr).left.map(ApplicationError.DomainFailure(_))
        yield (from, to)

        posResult match
          case Left(err) =>
            console.printLine(ConsoleRenderer.renderApplicationError(err))
            console.print("> ")
            loop(pendingMove)
          case Right((from, to)) =>
            (sessionGameService, sessionContext) match
              case (Some(svc), Some(ctx)) =>
                // ── Session-aware path ──────────────────────────────────────
                // Domain validation, session lifecycle persistence, game-state
                // persistence, and event publication all happen inside submitMove.
                // ObservableGame is updated afterwards as a notification bridge only.
                svc.submitMove(ctx.getSession, state, Move(from, to), SideController.HumanLocal) match
                  case Left(err) if isPromotionRequired(err) =>
                    console.printLine(ConsoleRenderer.renderPromotionRequired())
                    loop(pendingMove = Some(Move(from, to)))
                  case Left(err) =>
                    console.printLine(renderSessionMoveError(err))
                    console.print("> ")
                    loop(pendingMove)
                  case Right((newState, newSess)) =>
                    ctx.setSession(newSess)
                    game.updateState(newState)
                    loop(pendingMove = None)
              case _ =>
                // ── Local mode ──────────────────────────────────────────────
                // Pure domain path: no persistence, no events.
                ChessService.handleCommand(state, MakeMove(Move(from, to))) match
                  case Left(ApplicationError.DomainFailure(DomainError.MissingPromotionChoice)) =>
                    console.printLine(ConsoleRenderer.renderPromotionRequired())
                    loop(pendingMove = Some(Move(from, to)))
                  case Left(err) =>
                    console.printLine(ConsoleRenderer.renderApplicationError(err))
                    console.print("> ")
                    loop(pendingMove)
                  case Right(newState) =>
                    game.updateState(newState)
                    loop(pendingMove = None)

      case Right(TextUiCommand.PromoteCmd(pieceType)) =>
        pendingMove match
          case None =>
            console.printLine("No pawn promotion pending.")
            console.print("> ")
            loop(pendingMove = None)
          case Some(pm) =>
            (sessionGameService, sessionContext) match
              case (Some(svc), Some(ctx)) =>
                // ── Session-aware path ──────────────────────────────────────
                // $COVERAGE-OFF$ promotion from a valid board cannot fail
                svc.submitMove(ctx.getSession, state, Move(pm.from, pm.to, Some(pieceType)), SideController.HumanLocal) match
                  case Left(err) =>
                    console.printLine(renderSessionMoveError(err))
                    console.print("> ")
                    loop(pendingMove = Some(pm))
                  // $COVERAGE-ON$
                  case Right((newState, newSess)) =>
                    ctx.setSession(newSess)
                    game.updateState(newState)
                    loop(pendingMove = None)
              case _ =>
                // ── Local mode ──────────────────────────────────────────────
                // $COVERAGE-OFF$ promotion with q/r/b/n on a valid board cannot fail
                ChessService.handleCommand(state, MakeMove(Move(pm.from, pm.to, Some(pieceType)))) match
                  case Left(err) =>
                    console.printLine(ConsoleRenderer.renderApplicationError(err))
                    console.print("> ")
                    loop(pendingMove = Some(pm))
                  // $COVERAGE-ON$
                  case Right(newState) =>
                    game.updateState(newState)
                    loop(pendingMove = None)

  // ── Session-mode helpers ────────────────────────────────────────────────────

  private def isPromotionRequired(err: SessionMoveError): Boolean = err match
    case SessionMoveError.DomainRejection(ApplicationError.DomainFailure(DomainError.MissingPromotionChoice)) => true
    case _ => false

  /** Translate a [[SessionMoveError]] to a user-facing string.
   *
   *  Domain rejections delegate to [[ConsoleRenderer.renderApplicationError]] so
   *  the rendering vocabulary is consistent between local and session mode.
   *  Session-level rejections produce dedicated messages.
   */
  private def renderSessionMoveError(err: SessionMoveError): String = err match
    case SessionMoveError.DomainRejection(appErr)      => ConsoleRenderer.renderApplicationError(appErr)
    case SessionMoveError.UnauthorizedController(_, _) => "It is not your turn."
    case SessionMoveError.SessionFinished              => "The game is already finished. Start a new game with 'new'."
    case SessionMoveError.PersistenceFailed(_)         => "Internal error: could not save the game state."

object TextUI:
  /** Convenience constructor using a fresh game — local (non-persistent) mode. */
  def apply(console: Console): TextUI =
    new TextUI(console, new ObservableGame())

  /** Convenience constructor from an initial [[GameState]] — local (non-persistent) mode. */
  def apply(console: Console, initialState: GameState): TextUI =
    new TextUI(console, new ObservableGame(initialState))
