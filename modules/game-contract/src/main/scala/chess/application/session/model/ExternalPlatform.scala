package chess.application.session.model

/** Identifies the external platform that mediates a game through the external-game integration.
  *
  * Used by [[SideController.External]] to tag a seat as being driven by an actor on a specific
  * external platform, and by [[chess.application.external.ExternalGameBinding]] to scope the
  * uniqueness constraint on external game identifiers.
  */
enum ExternalPlatform:
  case Lichess
