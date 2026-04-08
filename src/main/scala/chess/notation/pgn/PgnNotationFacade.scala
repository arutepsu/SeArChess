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
    (parsed, target) match
      case (pgn: ParsedNotation.ParsedPgn, ImportTarget.GameTarget) =>
        // Minimal: parse moves, replay on new game
        chess.notation.pgn.PgnParser.parseMoves(pgn.moveText) match
          case Left(err) => Left(ImportFailure.MappingError(err))
          case Right(moveStrs) =>
            def parseMoveStr(str: String): Either[String, chess.domain.model.Move] = {
              val moveRegex = "Move\\(([^,]+),([^,]+),(.+)\\)".r
              str match {
                case moveRegex(fromStr, toStr, promoStr) =>
                  for {
                    from <- chess.domain.model.Position.fromAlgebraic(fromStr.trim).left.map(_.toString)
                    to   <- chess.domain.model.Position.fromAlgebraic(toStr.trim).left.map(_.toString)
                    promo = promoStr.trim match {
                      case "None" => None
                      case s if s.startsWith("Some(") && s.endsWith(")") =>
                        val inner = s.stripPrefix("Some(").stripSuffix(")")
                        Some(chess.domain.model.PieceType.valueOf(inner))
                      case other => Some(chess.domain.model.PieceType.valueOf(other))
                    }
                  } yield chess.domain.model.Move(from, to, promo)
                case _ => Left(s"Cannot parse move: $str")
              }
            }
            val initial = chess.application.ChessService.createNewGame()
            val movesOrErrors = moveStrs.foldLeft(Right(initial): Either[String, GameState]) { (acc, moveStr) =>
              for {
                state <- acc
                move  <- parseMoveStr(moveStr)
                next  <- chess.application.ChessService.handleCommand(state, chess.application.ChessCommand.MakeMove(move)).left.map(_.toString)
              } yield next
            }
            movesOrErrors match
              case Left(err) => Left(ImportFailure.MappingError(err))
              case Right(finalState) =>
                Right(ImportResult.GameImportResult(
                  data = finalState,
                  sourceFormat = NotationFormat.PGN,
                  metadata = GameImportMetadata(),
                  replay = Some(ReplaySummary(moveCount = Some(moveStrs.length))),
                  warnings = Nil
                ))
      case (pgn: ParsedNotation.ParsedPgn, ImportTarget.PositionTarget) =>
        Left(ImportFailure.IncompatibleTarget(
          parsedKind = ParsedNotationKind.Pgn,
          target = target,
          message = "PGN import only supports GameTarget"
        ))
      case _ =>
        Left(ImportFailure.IncompatibleTarget(
          parsedKind = parsed.kind,
          target = target,
          message = "PGN import: incompatible target or IR"
        ))

  override def executeExport(
    data: GameState,
    format: NotationFormat
  ): Either[NotationFailure, ExportResult] =
    format match
      case NotationFormat.PGN =>
        val moves: List[String] = data.moveHistory.map(_.toString) // Replace with algebraic if available
        val pgn = chess.notation.pgn.PgnExporter.exportMoves(moves)
        Right(ExportResult(pgn, NotationFormat.PGN))
      case other =>
        Left(ExportFailure.UnsupportedExportFormat(other, s"Export to $other is not implemented"))
