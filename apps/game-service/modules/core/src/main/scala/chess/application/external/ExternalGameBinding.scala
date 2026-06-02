package chess.application.external

import chess.application.session.model.ExternalPlatform
import chess.application.session.model.SessionIds.{GameId, SessionId}
import java.time.Instant
import java.util.UUID

final case class BindingId(value: UUID) extends AnyVal

object BindingId:
  def random(): BindingId = BindingId(UUID.randomUUID())

enum BindingStatus:
  case Active
  case Finished
  case Failed(reason: String)

/** Durable link between a game on an external platform and an internal Game Service session/game.
  *
  * ===Uniqueness invariant===
  * `(platform, externalGameId)` is a unique key — enforced in the persistence layer by a UNIQUE
  * constraint. [[ExternalGameService.startExternalGame]] uses this as an idempotency guard: if a
  * binding already exists for a given external game, it is returned as-is without creating a second
  * session.
  *
  * ===`lastProcessedPly`===
  * External platforms (e.g. Lichess) deliver the full cumulative move history on every state event.
  * `lastProcessedPly` is the number of moves that have already been applied to the internal game
  * state, counting both externally-submitted moves and AI-generated moves. Incoming move lists with
  * `count <= lastProcessedPly` are no-ops. Writes that advance the ply are performed atomically
  * with the session+state update via [[chess.application.port.repository.ExternalGameCommandStore]].
  *
  * ===`ourActorId`===
  * The platform identity of the bot/service that created this binding. Used by
  * [[chess.application.external.ExternalGameService]] to verify that a
  * [[VerifiedExternalCaller]] is authorised to operate on the binding. Checked at the application
  * service layer, not at the policy layer.
  *
  * @param bindingId        internal identity of this binding record
  * @param platform         which external platform hosts this game
  * @param externalGameId   the game's identifier on the external platform (opaque string)
  * @param internalGameId   FK to the internal game state record
  * @param sessionId        FK to the internal session record
  * @param ourActorId       platform actor ID of the bot/caller that owns this binding
  * @param status           current lifecycle status of the binding
  * @param lastProcessedPly number of external+AI plies already applied
  * @param createdAt        wall-clock instant when the binding was created
  * @param updatedAt        wall-clock instant of the most recent status/ply change
  */
final case class ExternalGameBinding(
    bindingId: BindingId,
    platform: ExternalPlatform,
    externalGameId: String,
    internalGameId: GameId,
    sessionId: SessionId,
    ourActorId: String,
    status: BindingStatus,
    lastProcessedPly: Int,
    createdAt: Instant,
    updatedAt: Instant
)
