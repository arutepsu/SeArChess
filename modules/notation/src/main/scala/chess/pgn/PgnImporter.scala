package chess.notation.pgn

import chess.domain.state.{GameState, GameStateFactory}
import chess.notation.api._

/** Maps a [[ParsedNotation.ParsedPgn]] to a [[ImportResult.GameImportResult]]
 *  by replaying the SAN mainline tokens from the standard starting position.
 *
 *  Target and kind routing:
 *  - [[ImportTarget.PositionTarget]] → [[ImportFailure.IncompatibleTarget]] (PGN
 *    represents a game, not a bare position)
 *  - Any non-PGN [[ParsedNotation]] → [[ImportFailure.IncompatibleTarget]]
 *  - PGN with SetUp/FEN headers → [[CompatibilityFailure.UnsupportedDialect]]
 *    (non-standard starting positions are not yet supported)
 *
 *  Visible only within the `chess.notation.pgn` package.
 */
private[pgn] object PgnImporter extends NotationImporter[GameState]:

  override def importNotation(
    parsed: ParsedNotation,
    target: ImportTarget
  ): Either[NotationFailure, ImportResult[GameState]] =
    parsed match
      case pgn: ParsedNotation.ParsedPgn =>
        importPgn(pgn, target)
      case other =>
        Left(ImportFailure.IncompatibleTarget(
          other.kind,
          target,
          s"PgnImporter cannot handle ${other.kind} notation"
        ))

  private def importPgn(
    pgn:    ParsedNotation.ParsedPgn,
    target: ImportTarget
  ): Either[NotationFailure, ImportResult[GameState]] =
    target match
      case ImportTarget.PositionTarget =>
        Left(ImportFailure.IncompatibleTarget(
          ParsedNotationKind.Pgn,
          ImportTarget.PositionTarget,
          "PGN represents a game sequence; use GameTarget to import it"
        ))
      case ImportTarget.GameTarget =>
        importGame(pgn)

  private def importGame(
    pgn: ParsedNotation.ParsedPgn
  ): Either[NotationFailure, ImportResult.GameImportResult[GameState]] =
    // Reject non-standard starting positions (SetUp + FEN header pair)
    if pgn.data.headers.get("SetUp").contains("1") && pgn.data.headers.contains("FEN") then
      return Left(CompatibilityFailure.UnsupportedDialect(
        "SetUp/FEN",
        "PGN games with a custom starting position (SetUp \"1\"/FEN header) are not supported"
      ))

    val tokens = pgn.data.moveTokens

    PgnReplayService.replayFrom(GameStateFactory.initial(), tokens).map { finalState =>
      val metadata = GameImportMetadata(
        normalized                  = false,
        sourceDialect               = None,
        sourceVersion               = None,
        hasStartingPositionOverride = false
      )
      val replay = ReplaySummary(
        moveCount                   = Some(tokens.length),
        isFullReplay                = true,
        hasStartingPositionOverride = false
      )
      ImportResult.GameImportResult(
        data         = finalState,
        sourceFormat = NotationFormat.PGN,
        metadata     = metadata,
        replay       = Some(replay)
      )
    }
