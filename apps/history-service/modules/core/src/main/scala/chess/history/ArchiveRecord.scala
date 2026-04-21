package chess.history

import chess.application.query.game.GameClosure
import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import java.time.Instant

/** Durable archive-ready record for a completed or closed game session.
  *
  * Produced by [[ArchiveMaterializer.materialize]] from a
  * [[chess.application.query.game.GameArchiveSnapshot]]. Carries both the semantic closure and the
  * pre-rendered notation strings so that downstream History consumers (search, replay, export)
  * never need to re-derive them.
  *
  * ==Field notes==
  *   - [[pgn]] — `None` only for cancelled sessions with an empty move history (there is no
  *     meaningful game to record); `Some` for all completed games and cancelled games with moves
  *   - [[finalFen]] — FEN of the position at close time; always present when materialization
  *     succeeds
  *   - [[closure]] — authoritative reason the game ended; use this in preference to parsing the PGN
  *     result tag
  */
final case class ArchiveRecord(
    gameId: GameId,
    sessionId: SessionId,
    mode: SessionMode,
    whiteController: SideController,
    blackController: SideController,
    closure: GameClosure,
    pgn: Option[String],
    finalFen: Option[String],
    createdAt: Instant,
    closedAt: Instant,
    materializedAt: Instant
)
