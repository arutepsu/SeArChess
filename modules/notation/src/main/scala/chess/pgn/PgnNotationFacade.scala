package chess.notation.pgn

import chess.domain.state.GameState
import chess.notation.api._

/** The single canonical entry point for all PGN notation operations.
  *
  * All callers must go through this facade rather than using any internal helpers directly.
  * Internal objects (`PgnParser`) are `private[pgn]` and not part of the public API.
  *
  * Current implementation status:
  *   - `parse` — fully implemented: extracts headers, move tokens, and result token into
  *     [[ParsedNotation.ParsedPgn]]
  *   - `executeImport` — fully implemented: replays the SAN mainline from the standard starting
  *     position via [[PgnImporter]]
  *   - `executeExport` — fully implemented: serialises [[chess.domain.state.GameState]] move
  *     history to PGN movetext via [[PgnExporter]]; no headers are emitted (GameState carries no
  *     game metadata)
  *   - `exportWithHeaders` — assembles a complete PGN document from caller-supplied headers and the
  *     move body derived from [[PgnExporter]]; intended for History / archive materialisation where
  *     session metadata (players, date, result) is known
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
      data: GameState,
      format: NotationFormat
  ): Either[NotationFailure, ExportResult] =
    PgnExporter.exportNotation(data, format)

  /** Assemble a complete PGN document with caller-supplied headers and the move body derived from
    * [[data]].
    *
    * Headers are emitted in the order provided — callers must supply the Seven Tag Roster in the
    * standard PGN order (Event, Site, Date, Round, White, Black, Result) if compliance is required.
    * A blank line separates the header block from the movetext, as required by the PGN standard.
    *
    * An empty [[headers]] sequence produces a header-less document identical to [[executeExport]]
    * with [[NotationFormat.PGN]].
    *
    * @param data
    *   the game state whose move history is serialised as movetext
    * @param headers
    *   ordered tag pairs (name → value) to emit before the movetext
    * @return
    *   [[ExportResult]] whose `text` is a well-formed PGN document, or a [[NotationFailure]] if
    *   move serialisation fails
    */
  def exportWithHeaders(
      data: GameState,
      headers: Seq[(String, String)]
  ): Either[NotationFailure, ExportResult] =
    PgnExporter.exportNotation(data, NotationFormat.PGN).map { result =>
      val headerBlock = headers.map { case (k, v) => s"""[$k "$v"]""" }.mkString("\n")
      val fullText =
        if headerBlock.isEmpty then result.text
        else s"$headerBlock\n\n${result.text}"
      result.copy(text = fullText)
    }
