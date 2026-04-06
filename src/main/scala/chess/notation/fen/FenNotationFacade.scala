package chess.notation.fen

import chess.domain.state.GameState
import chess.notation.api.{
  ImportResult, ImportTarget, NotationFacade, NotationFailure,
  NotationFormat, ParsedNotation, ParseFailure
}

/** Concrete [[NotationFacade]] wiring [[FenParser]] and [[FenImporter]].
 *
 *  This is the canonical pre-assembled facade for position-level FEN
 *  import operations.  It handles only [[NotationFormat.FEN]]; all other
 *  formats fail at the parse stage with a structured [[ParseFailure]].
 *
 *  Placed in `chess.notation.fen` because it composes notation-internal
 *  objects ([[FenParser]], [[FenImporter]]) and does not belong in any
 *  adapter or application layer.
 */
object FenNotationFacade extends NotationFacade[GameState]:

  def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
    format match
      case NotationFormat.FEN => FenParser.parse(input)
      case other              => Left(ParseFailure.StructuralError(s"No parser available for format: $other"))

  def executeImport(
      parsed: ParsedNotation,
      target: ImportTarget
  ): Either[NotationFailure, ImportResult[GameState]] =
    FenImporter.importNotation(parsed, target)
