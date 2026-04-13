package chess.notation.pgn

import chess.domain.state.GameState
import chess.notation.api._

/** The single canonical entry point for all PGN notation operations.
 *
 *  All callers must go through this facade rather than using any internal
 *  helpers directly.  Internal objects (`PgnParser`) are `private[pgn]`
 *  and not part of the public API.
 *
 *  Current implementation status:
 *  - `parse`         — fully implemented: extracts headers, move tokens, and
 *                      result token into [[ParsedNotation.ParsedPgn]]
 *  - `executeImport` — fully implemented: replays the SAN mainline from the
 *                      standard starting position via [[PgnImporter]]
 *  - `executeExport` — fully implemented: serialises [[chess.domain.state.GameState]]
 *                      move history to PGN movetext via [[PgnExporter]];
 *                      no headers are emitted (GameState carries no game metadata)
 */
object PgnNotationFacade extends NotationFacade[GameState]:

  override def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
    format match
      case NotationFormat.PGN => PgnParser.parseParsedNotation(input)
      case other              => Left(ParseFailure.StructuralError(s"No parser for format: $other"))

  override def executeImport(
    parsed: ParsedNotation,
    target: ImportTarget
  ): Either[NotationFailure, ImportResult[GameState]] =
    PgnImporter.importNotation(parsed, target)

  override def executeExport(
    data:   GameState,
    format: NotationFormat
  ): Either[NotationFailure, ExportResult] =
    PgnExporter.exportNotation(data, format)
