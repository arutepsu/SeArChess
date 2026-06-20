package chess.application.session.policy

import chess.application.session.model.{GameSession, SideController}
import chess.domain.model.Color

/** Pure policy that decides whether a given controller is authorised to act for the side that is
  * currently to move.
  *
  * ===Design intent===
  * This policy operates exclusively in terms of application-layer concepts ([[GameSession]],
  * [[SideController]], [[Color]]). It has no knowledge of HTTP sessions, websocket connections, GUI
  * focus, or network identities. Those concerns are translated into a [[SideController]] value by
  * the relevant adapter before calling this policy.
  *
  * ===Usage===
  * {{{
  *  val allowed = ActorControlPolicy.canAct(session, requestingController, state.currentPlayer)
  * }}}
  *
  * The `requestingController` is the identity the adapter has determined for the actor who wants to
  * move (e.g. `SideController.HumanLocal` for a local GUI click, `SideController.AI("stockfish")`
  * for an engine callback).
  */
object ActorControlPolicy:

  /** Returns `true` when `requestingController` matches the controller assigned to `sideToMove` in
    * `session`.
    *
    * Matching rules:
    *   - [[SideController.HumanLocal]] matches only [[SideController.HumanLocal]].
    *   - [[SideController.HumanRemote]] matches only [[SideController.HumanRemote]].
    *   - [[SideController.AI]] matches any [[SideController.AI]] value. Engine id is NOT checked
    *     here: finer engine-identity checks belong in the AI service layer, not in the general
    *     turn-ownership policy.
    *
    * @param session
    *   the current session (provides controller assignments)
    * @param requestingController
    *   the controller that is requesting to act
    * @param sideToMove
    *   the color whose turn it is according to the domain
    */
  def canAct(
      session: GameSession,
      requestingController: SideController,
      sideToMove: Color
  ): Boolean =
    val assigned = sideToMove match
      case Color.White => session.whiteController
      case Color.Black => session.blackController
    matches(assigned, requestingController)

  /** Controller matching predicate.
    *
    * `AI` variants match each other regardless of `engineId` because turn ownership is about actor
    * type, not specific engine identity.
    *
    * `External` variants match when platforms agree. The `actorId` is intentionally NOT checked
    * here: fine-grained identity validation (ensuring the calling bot matches the session's
    * assigned actor) is the responsibility of the external-game application service, which operates
    * on a [[chess.adapter.http4s.VerifiedCallerIdentity]] extracted from the request by the route.
    * Checking `actorId` at the policy level would re-open the attack surface by trusting request
    * body values — exactly what [[chess.adapter.http4s.VerifiedCallerIdentity]] prevents.
    */
  private def matches(assigned: SideController, requesting: SideController): Boolean =
    (assigned, requesting) match
      case (SideController.HumanLocal, SideController.HumanLocal)   => true
      case (SideController.HumanRemote, SideController.HumanRemote) => true
      case (SideController.AI(_), SideController.AI(_))             => true
      case (SideController.External(p1, _), SideController.External(p2, _)) => p1 == p2
      case (SideController.DeployedBot, SideController.DeployedBot)          => true
      case _                                                                 => false
