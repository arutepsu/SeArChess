package chess.application.session.model

/** Single authoritative [[GameSession]] holder shared between desktop adapters.
  *
  * In desktop mode the GUI and TUI operate over one authoritative session identity. Instead of each
  * adapter maintaining an independent `var currentSession`, both read and write through this shared
  * context so that a reset from either adapter is immediately visible to the other.
  *
  * The `@volatile` field guarantees that writes on the TUI daemon thread are visible to the JavaFX
  * application thread without requiring explicit locking. In the in-process desktop deployment only
  * one adapter writes at a time (moves are turn-based), so the volatile write/read is sufficient.
  */
class DesktopSessionContext(initialSession: GameSession):
  @volatile private var session: GameSession = initialSession

  def getSession: GameSession = session

  def setSession(newSession: GameSession): Unit =
    session = newSession
