package chess.history

import chess.application.query.game.{GameArchiveSnapshot, GameClosure}
import chess.domain.model.Color
import chess.domain.state.GameState
import chess.notation.api.{ExportResult, NotationFailure, NotationFormat}
import chess.notation.fen.FenNotationFacade
import chess.notation.pgn.PgnNotationFacade
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}

/** Turns a [[GameArchiveSnapshot]] into a durable [[ArchiveRecord]] by deriving FEN and PGN
  * notation from the snapshot's final game state.
  *
  * ===Design===
  * The two exporter functions are injected so that tests can verify error propagation without
  * depending on the real notation engine. Production callers use [[ArchiveMaterializer.apply]]
  * which wires the real facades.
  *
  * ===Usage from a future History subscriber===
  * {{{
  *    val materializer = ArchiveMaterializer()
  *    // On receiving a terminal AppEvent:
  *    gameService.getArchiveSnapshot(gameId).flatMap { snapshot =>
  *      materializer.materialize(snapshot).left.map(handleMaterializeError)
  *    }
  * }}}
  *
  * @param fenExport
  *   derive FEN text from a [[GameState]]
  * @param pgnExport
  *   derive PGN document from a [[GameState]] and header pairs
  */
class ArchiveMaterializer private (
    fenExport: GameState => Either[NotationFailure, ExportResult],
    pgnExport: (GameState, Seq[(String, String)]) => Either[NotationFailure, ExportResult]
):

  def materialize(
      snapshot: GameArchiveSnapshot,
      now: Instant = Instant.now()
  ): Either[ArchiveMaterializeError, ArchiveRecord] =
    val state = snapshot.finalState.toGameState
    for
      fenResult <- fenExport(state).left.map(e =>
        ArchiveMaterializeError.FenExportFailed(e.toString)
      )
      pgnOpt <- derivePgn(snapshot, state)
    yield ArchiveRecord(
      gameId = snapshot.gameId,
      sessionId = snapshot.sessionId,
      mode = snapshot.mode,
      whiteController = snapshot.whiteController,
      blackController = snapshot.blackController,
      closure = snapshot.closure,
      pgn = pgnOpt,
      finalFen = Some(fenResult.text),
      createdAt = snapshot.createdAt,
      closedAt = snapshot.closedAt,
      materializedAt = now
    )

  // ── Private helpers ──────────────────────────────────────────────────────────

  private def derivePgn(
      snapshot: GameArchiveSnapshot,
      state: GameState
  ): Either[ArchiveMaterializeError, Option[String]] =
    // A cancelled game with no moves has no game to record — skip PGN.
    if snapshot.closure == GameClosure.Cancelled && state.moveHistory.isEmpty then Right(None)
    else
      pgnExport(state, buildHeaders(snapshot))
        .map(r => Some(r.text))
        .left
        .map(e => ArchiveMaterializeError.PgnExportFailed(e.toString))

  private val dateFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC)

  private def buildHeaders(snapshot: GameArchiveSnapshot): Seq[(String, String)] =
    Seq(
      "Event" -> "?",
      "Site" -> "searchess",
      "Date" -> dateFmt.format(snapshot.createdAt),
      "Round" -> "?",
      "White" -> controllerLabel(snapshot.whiteController),
      "Black" -> controllerLabel(snapshot.blackController),
      "Result" -> pgnResultTag(snapshot.closure)
    )

  private def controllerLabel(c: chess.application.session.model.SideController): String =
    import chess.application.session.model.SideController.*
    c match
      case HumanLocal       => "HumanLocal"
      case HumanRemote      => "HumanRemote"
      case AI(Some(engine)) => s"AI:$engine"
      case AI(None)         => "AI"

  private def pgnResultTag(closure: GameClosure): String = closure match
    case GameClosure.Checkmate(Color.White) => "1-0"
    case GameClosure.Checkmate(Color.Black) => "0-1"
    case GameClosure.Resigned(Color.White)  => "1-0"
    case GameClosure.Resigned(Color.Black)  => "0-1"
    case GameClosure.Draw(_)                => "1/2-1/2"
    case GameClosure.Cancelled              => "*"

object ArchiveMaterializer:

  /** Production instance wired to the real FEN and PGN notation facades. */
  def apply(): ArchiveMaterializer = new ArchiveMaterializer(
    fenExport = s => FenNotationFacade.executeExport(s, NotationFormat.FEN),
    pgnExport = PgnNotationFacade.exportWithHeaders
  )

  /** Test-only factory for injecting alternative export functions. */
  def withExporters(
      fenExport: GameState => Either[NotationFailure, ExportResult],
      pgnExport: (GameState, Seq[(String, String)]) => Either[NotationFailure, ExportResult]
  ): ArchiveMaterializer =
    new ArchiveMaterializer(fenExport, pgnExport)
