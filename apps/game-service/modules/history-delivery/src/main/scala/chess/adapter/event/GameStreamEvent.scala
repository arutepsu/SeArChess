package chess.adapter.event

import chess.application.event.AppEvent

object GameStreamEvent:

  val StreamName = HistoryArchiveStreamEvent.StreamName

  def eventTypeTag(event: AppEvent): Option[String] = event match
    case _: AppEvent.GameFinished     => Some("game.finished.v1")
    case _: AppEvent.GameResigned     => Some("game.resigned.v1")
    case _: AppEvent.SessionCancelled => Some("game.session.cancelled.v1")
    case _                            => None
