package chess.application.session.model

/** Represents who or what controls one side of the board.
  *
  * This value is exposed in Game Service contracts as session/game metadata.
  */
enum SideController:
  case HumanLocal
  case HumanRemote
  case AI(engineId: Option[String] = None)
