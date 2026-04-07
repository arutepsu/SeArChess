package chess.notation.pgn

import chess.domain.state.GameState
import chess.domain.model.Move
import chess.notation.api._

/** Concrete NotationFacade for PGN import/export (export only for now). */
object PgnNotationFacade extends NotationFacade[GameState]:

  override def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
    format match
      case NotationFormat.PGN =>
        // Minimal: keine Header, nur Züge
        val movesSection = input
        Right(ParsedNotation.ParsedPgn(
          raw = input,
          headers = Map.empty,
          moveText = movesSection
        ))
      case other =>
        Left(ParseFailure.StructuralError(s"No parser for format: $other"))

  override def executeImport(
    parsed: ParsedNotation,
    target: ImportTarget
  ): Either[NotationFailure, ImportResult[GameState]] =
    // Always return unavailable for PGN import (feature unavailable)
    Left(ImportFailure.MappingError("PGN import is not implemented"))

  override def executeExport(
    data: GameState,
    format: NotationFormat
  ): Either[NotationFailure, ExportResult] =
    // Always return unsupported for PGN export (feature unavailable)
    Left(ExportFailure.UnsupportedExportFormat(format, s"Export to $format is not implemented"))
