package chess.application.session.model

/** Describes the match configuration between the two sides.
  *
  * This value appears in Game Service contracts consumed by downstream services, including archive
  * snapshots.
  *
  * [[AiVsExternal]]: one side is driven by the internal AI engine; the other side is mediated
  * through an external platform adapter (e.g. a Lichess game stream). Sessions of this mode are
  * created and managed by the external-game application service, not the generic session endpoint.
  *
  * [[ExternalVsExternal]]: both sides are externally mediated. No internal AI is involved. The
  * external-game service submits moves for both sides (e.g. relaying a Lichess game into
  * Game Service for authoritative record-keeping or testing with two external actors).
  */
enum SessionMode:
  case HumanVsHuman
  case HumanVsAI
  case AIVsAI
  /** Human plays one side; the deployed [[SideController.DeployedBot]] worker plays the other.
    *
    * Sessions of this mode are created through `POST /sessions/human-vs-deployed-bot`,
    * which requires a verified linked Lichess account as an eligibility prerequisite.
    * The game is played entirely inside the Searchess Web UI; no Lichess API is called.
    */
  case HumanVsDeployedBot
  case AiVsExternal
  case ExternalVsExternal
