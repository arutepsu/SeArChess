package chess.notation.fen

import chess.notation.api.{
  ImportFailure, ImportResult, ImportTarget,
  NotationFailure, NotationFormat, NotationImporter,
  ParsedNotation, PositionImportMetadata
}
import chess.domain.state.GameState

/** FEN importer: maps a [[ParsedNotation.ParsedFen]] into a domain [[GameState]].
 *
 *  Implements [[NotationImporter]] for the [[ImportTarget.PositionTarget]] use case.
 *
 *  Responsibilities (orchestration only):
 *  - accept FEN parsed notation; reject other notation kinds
 *  - reject [[ImportTarget.GameTarget]] with a structured failure
 *  - delegate semantic validation to [[FenSemanticValidator]]
 *  - delegate domain mapping to [[FenToGameStateMapper]]
 *  - build the [[ImportResult.PositionImportResult]] wrapper with clock metadata
 *
 *  Intentionally NOT supported:
 *  - [[ImportTarget.GameTarget]] — FEN encodes a position snapshot, not a full game
 */
object FenImporter extends NotationImporter[GameState]:

  def importNotation(
      parsed: ParsedNotation,
      target: ImportTarget
  ): Either[NotationFailure, ImportResult[GameState]] =
    target match
      case ImportTarget.GameTarget =>
        Left(ImportFailure.IncompatibleTarget(
          parsed.kind,
          ImportTarget.GameTarget,
          "FEN encodes a single position snapshot; game import is not supported for FEN"
        ))
      case ImportTarget.PositionTarget =>
        parsed match
          case fen: ParsedNotation.ParsedFen =>
            importPosition(fen)
          case other =>
            Left(ImportFailure.IncompatibleTarget(
              other.kind,
              ImportTarget.PositionTarget,
              s"FenImporter only accepts ParsedFen; received ${other.kind}"
            ))

  private def importPosition(
      fen: ParsedNotation.ParsedFen
  ): Either[NotationFailure, ImportResult[GameState]] =
    val data = fen.data
    for
      _         <- (FenSemanticValidator.validate(data): Either[NotationFailure, Unit])
      gameState  = FenToGameStateMapper.map(data)
    yield ImportResult.PositionImportResult(
      data         = gameState,
      sourceFormat = NotationFormat.FEN,
      metadata     = PositionImportMetadata(
        halfmoveClock  = Some(data.halfmoveClock),
        fullmoveNumber = Some(data.fullmoveNumber)
      )
    )
