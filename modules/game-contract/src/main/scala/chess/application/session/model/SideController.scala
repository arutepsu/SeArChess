package chess.application.session.model

/** Represents who or what controls one side of the board.
  *
  * This value is exposed in Game Service contracts as session/game metadata.
  *
  * [[External]] models a seat driven by an actor on an external platform (e.g. Lichess). The
  * `actorId` is the platform's user identifier for that actor. Authorization that the requesting
  * caller matches `actorId` is enforced by the external-game application service, not by
  * [[chess.application.session.policy.ActorControlPolicy]].
  */
enum SideController:
  case HumanLocal
  case HumanRemote
  case AI(engineId: Option[String] = None)
  case External(platform: ExternalPlatform, actorId: String)
