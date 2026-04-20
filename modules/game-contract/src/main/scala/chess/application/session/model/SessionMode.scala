package chess.application.session.model

/** Describes the match configuration between the two sides.
 *
 *  This value appears in Game Service contracts consumed by downstream
 *  services, including archive snapshots.
 */
enum SessionMode:
  case HumanVsHuman
  case HumanVsAI
  case AIVsAI
