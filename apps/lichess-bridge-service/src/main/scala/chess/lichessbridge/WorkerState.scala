package chess.lichessbridge

import java.time.Instant

/** Mutable state held in a cats-effect Ref and shared between the worker and the HTTP routes.
  *
  * Safe to read from any thread. Updated only by the worker fiber (or game-loop fibers for metadata).
  */
final case class WorkerState(
    running: Boolean,
    activeGames: Map[String, ActiveGame],
    lastEventAt: Option[Instant],
    lastError: Option[String]
):
  def addGame(game: ActiveGame): WorkerState  = copy(activeGames = activeGames + (game.gameId -> game))
  def removeGame(gameId: String): WorkerState = copy(activeGames = activeGames - gameId)
  def withEvent(at: Instant): WorkerState     = copy(lastEventAt = Some(at), lastError = None)
  def withError(msg: String): WorkerState     = copy(lastError = Some(msg.take(200)))
  def started: WorkerState                    = copy(running = true)
  def stopped: WorkerState                    = copy(running = false)

  def updateGameMeta(gameId: String, f: ActiveGame => ActiveGame): WorkerState =
    activeGames.get(gameId) match
      case Some(game) => copy(activeGames = activeGames.updated(gameId, f(game)))
      case None       => this

object WorkerState:
  val empty: WorkerState = WorkerState(running = false, Map.empty, None, None)
