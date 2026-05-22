package chess.adapter.event

import chess.application.event.AppEvent

object GameStreamEvent:

<<<<<<< HEAD
  val StreamName = HistoryArchiveStreamEvent.StreamName

  def eventTypeTag(event: AppEvent): Option[String] = event match
=======
  val StreamName = "searchess:game-events"

  def eventTypeTag(event: AppEvent): Option[String] = event match
    case _: AppEvent.SessionCreated   => Some("game.session.created.v1")
    case _: AppEvent.MoveApplied      => Some("game.move.applied.v1")
>>>>>>> 8b003a1f (Use schema-isolated Slick Postgres persistence for history service)
    case _: AppEvent.GameFinished     => Some("game.finished.v1")
    case _: AppEvent.GameResigned     => Some("game.resigned.v1")
    case _: AppEvent.SessionCancelled => Some("game.session.cancelled.v1")
    case _                            => None
