package chess.adapter.gui.notation

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.application.ChessService
import chess.domain.model.{Color, PieceType}
import chess.domain.state.GameState
import chess.notation.api.{
  CompatibilityFailure,
  ExportFailure,
  ExportResult,
  GameImportMetadata,
  ImportFailure,
  ImportResult,
  ImportTarget,
  NotationFacade,
  NotationFailure,
  NotationFormat,
  NotationWarning,
  ParsedNotation,
  ParseFailure,
  PositionImportMetadata
}

/** Tests for [[GuiNotationApi]] at the GUI API boundary.
  *
  * All assertions against outcomes use [[GuiNotationOutcome]], [[FailureCategory]],
  * [[GuiNotationWarning]], and [[GuiWarningCategory]] only.
  *
  * Notation-layer ADTs (NotationFailure, ImportResult, NotationFormat, …) appear ONLY in stub
  * facades within this spec, confirming that those contracts are confined to the API implementation
  * and not visible to callers.
  */
class GuiNotationApiSpec extends AnyFlatSpec with Matchers:

  private val InitialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val freshState = ChessService.createNewGame()

  // ── FEN import: success ────────────────────────────────────────────────────

  "GuiNotationApi.importFen" should "return ImportSuccess for the standard starting position FEN" in {
    val outcome = GuiNotationApi.importFen(InitialFen)
    outcome shouldBe a[GuiNotationOutcome.ImportSuccess]
  }

  it should "produce a GameState with White to move for the starting FEN" in {
    val outcome =
      GuiNotationApi.importFen(InitialFen).asInstanceOf[GuiNotationOutcome.ImportSuccess]
    outcome.state.currentPlayer shouldBe Color.White
  }

  it should "carry no warnings for a clean standard FEN" in {
    val outcome =
      GuiNotationApi.importFen(InitialFen).asInstanceOf[GuiNotationOutcome.ImportSuccess]
    outcome.warnings shouldBe Nil
  }

  it should "expose warnings as List[GuiNotationWarning], not List[String]" in {
    val outcome =
      GuiNotationApi.importFen(InitialFen).asInstanceOf[GuiNotationOutcome.ImportSuccess]
    // Assigning to a typed val is a compile-time proof that warnings is List[GuiNotationWarning].
    val ws: List[GuiNotationWarning] = outcome.warnings
    ws shouldBe Nil
  }

  it should "return a GameState whose board matches the starting position" in {
    import chess.domain.model.{Piece, Position}
    val outcome =
      GuiNotationApi.importFen(InitialFen).asInstanceOf[GuiNotationOutcome.ImportSuccess]
    val e2 = Position.fromAlgebraic("e2").getOrElse(throw AssertionError("bad pos"))
    outcome.state.board.pieceAt(e2) shouldBe Some(Piece(Color.White, PieceType.Pawn))
  }

  it should "respect the active color encoded in the FEN" in {
    val blackToMoveFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
    val outcome =
      GuiNotationApi.importFen(blackToMoveFen).asInstanceOf[GuiNotationOutcome.ImportSuccess]
    outcome.state.currentPlayer shouldBe Color.Black
  }

  it should "thread halfmoveClock from the FEN into the GameState" in {
    val fenWith5Clock = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 5 1"
    val outcome =
      GuiNotationApi.importFen(fenWith5Clock).asInstanceOf[GuiNotationOutcome.ImportSuccess]
    outcome.state.halfmoveClock shouldBe 5
  }

  // ── FEN import: syntax failure ─────────────────────────────────────────────

  it should "return Failure(InvalidInput) for completely malformed input" in {
    val outcome = GuiNotationApi.importFen("not a fen at all")
    outcome shouldBe a[GuiNotationOutcome.Failure]
    outcome.asInstanceOf[GuiNotationOutcome.Failure].category shouldBe FailureCategory.InvalidInput
  }

  it should "include a non-empty message in the failure" in {
    val outcome = GuiNotationApi.importFen("bad").asInstanceOf[GuiNotationOutcome.Failure]
    outcome.message should not be empty
  }

  it should "return Failure(InvalidInput) for empty input" in {
    val outcome = GuiNotationApi.importFen("")
    outcome.asInstanceOf[GuiNotationOutcome.Failure].category shouldBe FailureCategory.InvalidInput
  }

  // ── FEN import: semantic failure ───────────────────────────────────────────

  it should "return Failure(SemanticError) when the black king is missing" in {
    val noBlackKingFen = "8/8/8/8/8/8/8/4K3 w - - 0 1"
    val outcome = GuiNotationApi.importFen(noBlackKingFen)
    outcome shouldBe a[GuiNotationOutcome.Failure]
    outcome.asInstanceOf[GuiNotationOutcome.Failure].category shouldBe FailureCategory.SemanticError
  }

  it should "return Failure(SemanticError) when castling rights contradict piece placement" in {
    val inconsistentFen = "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R4K1R w KQkq - 0 1"
    val outcome = GuiNotationApi.importFen(inconsistentFen)
    outcome.asInstanceOf[GuiNotationOutcome.Failure].category shouldBe FailureCategory.SemanticError
  }

  // ── PGN import: success ───────────────────────────────────────────────────

  "GuiNotationApi.importPgn" should "return ImportSuccess for a valid PGN game" in {
    val outcome = GuiNotationApi.importPgn("1. e4 e5 2. Nf3 Nc6 *")
    outcome shouldBe a[GuiNotationOutcome.ImportSuccess]
  }

  it should "produce the correct final player after replay" in {
    val outcome = GuiNotationApi
      .importPgn("1. e4 e5 2. Nf3 Nc6 *")
      .asInstanceOf[GuiNotationOutcome.ImportSuccess]
    outcome.state.currentPlayer shouldBe Color.White
  }

  it should "return Failure(InvalidInput) for syntactically invalid PGN" in {
    val outcome = GuiNotationApi.importPgn("")
    outcome shouldBe a[GuiNotationOutcome.Failure]
    outcome.asInstanceOf[GuiNotationOutcome.Failure].category shouldBe FailureCategory.InvalidInput
  }

  it should "return Failure(SemanticError) for an illegal SAN token" in {
    val outcome = GuiNotationApi.importPgn("1. e5")
    outcome shouldBe a[GuiNotationOutcome.Failure]
    outcome.asInstanceOf[GuiNotationOutcome.Failure].category shouldBe FailureCategory.SemanticError
  }

  // ── FEN export: success ───────────────────────────────────────────────────

  "GuiNotationApi.exportFen" should "return ExportSuccess for a valid GameState" in {
    val outcome = GuiNotationApi.exportFen(freshState)
    outcome shouldBe a[GuiNotationOutcome.ExportSuccess]
  }

  it should "produce the standard starting-position FEN for a fresh game" in {
    val outcome =
      GuiNotationApi.exportFen(freshState).asInstanceOf[GuiNotationOutcome.ExportSuccess]
    outcome.text shouldBe InitialFen
  }

  it should "produce a non-empty FEN string" in {
    val outcome =
      GuiNotationApi.exportFen(freshState).asInstanceOf[GuiNotationOutcome.ExportSuccess]
    outcome.text should not be empty
  }

  it should "produce a six-field FEN string" in {
    val outcome =
      GuiNotationApi.exportFen(freshState).asInstanceOf[GuiNotationOutcome.ExportSuccess]
    outcome.text.split(' ') should have length 6
  }

  // ── PGN export: success ───────────────────────────────────────────────────

  "GuiNotationApi.exportPgn" should "return ExportSuccess for a valid GameState" in {
    val outcome = GuiNotationApi.exportPgn(freshState)
    outcome shouldBe a[GuiNotationOutcome.ExportSuccess]
  }

  it should "produce a non-empty PGN string" in {
    val outcome =
      GuiNotationApi.exportPgn(freshState).asInstanceOf[GuiNotationOutcome.ExportSuccess]
    outcome.text should not be empty
  }

  it should "produce '*' result token for an ongoing game with no moves" in {
    val outcome =
      GuiNotationApi.exportPgn(freshState).asInstanceOf[GuiNotationOutcome.ExportSuccess]
    outcome.text shouldBe "*"
  }

  // ── Dependency injection / wiring boundary ─────────────────────────────────

  "GuiNotationApi (injected facade)" should "use the provided facade for FEN import" in {
    // Verify that a custom-injected facade is actually called, not the default.
    var called = false
    val stubFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        called = true
        Left(ParseFailure.StructuralError("stub"))
      def executeImport(
          parsed: ParsedNotation,
          target: ImportTarget
      ): Either[NotationFailure, ImportResult[GameState]] =
        Left(chess.notation.api.ImportFailure.MappingError("unreachable"))

    val api = GuiNotationApi(stubFacade)
    api.importFen("anything")
    called shouldBe true
  }

  it should "return Failure(InvalidInput) when the injected facade returns a parse failure" in {
    val failingFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        Left(ParseFailure.SyntaxError("stub syntax error", line = Some(1), column = Some(5)))
      def executeImport(
          parsed: ParsedNotation,
          target: ImportTarget
      ): Either[NotationFailure, ImportResult[GameState]] =
        Left(chess.notation.api.ImportFailure.MappingError("unreachable"))

    val outcome = GuiNotationApi(failingFacade).importFen("x")
    val failure = outcome.asInstanceOf[GuiNotationOutcome.Failure]
    failure.category shouldBe FailureCategory.InvalidInput
    failure.details shouldBe Some("Line 1, column 5")
  }

  // ── UnsupportedInput vs UnavailableFeature ─────────────────────────────────

  "GuiNotationApi" should "map CompatibilityFailure to Failure(UnsupportedInput)" in {
    val dialectFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        Left(ParseFailure.StructuralError("parsed"))
      def executeImport(
          parsed: ParsedNotation,
          target: ImportTarget
      ): Either[NotationFailure, ImportResult[GameState]] =
        Left(CompatibilityFailure.UnsupportedDialect("Chess960", "not supported"))

    // parseAndImport short-circuits at parse stage, so wire a facade that gets to executeImport
    val dialectFacade2 = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        chess.notation.fen.FenParser.parse(InitialFen) // parse succeeds
      def executeImport(
          parsed: ParsedNotation,
          target: ImportTarget
      ): Either[NotationFailure, ImportResult[GameState]] =
        Left(CompatibilityFailure.UnsupportedDialect("Chess960", "Chess960 is not supported"))

    val outcome = GuiNotationApi(dialectFacade2).importFen(InitialFen)
    val failure = outcome.asInstanceOf[GuiNotationOutcome.Failure]
    failure.category shouldBe FailureCategory.UnsupportedInput
  }

  it should "succeed for importPgn and exportPgn now that they are fully implemented" in {
    val api = GuiNotationApi.default
    api.importPgn("1. e4 e5 *") shouldBe a[GuiNotationOutcome.ImportSuccess]
    api.exportPgn(freshState) shouldBe a[GuiNotationOutcome.ExportSuccess]
  }

  // ── Structured warnings ────────────────────────────────────────────────────

  "GuiNotationApi" should "translate notation-layer warnings to GuiNotationWarning with correct category" in {
    val warningFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        chess.notation.fen.FenParser.parse(InitialFen)
      def executeImport(
          parsed: ParsedNotation,
          target: ImportTarget
      ): Either[NotationFailure, ImportResult[GameState]] =
        chess.notation.fen.FenImporter.importNotation(parsed, target).map {
          case r: ImportResult.PositionImportResult[GameState @unchecked] =>
            r.copy(warnings =
              List(
                NotationWarning.UnknownTag("Annotator"),
                NotationWarning.UnsupportedExtensionIgnored("NAG-7"),
                NotationWarning.NormalizationApplied("filled missing result token")
              )
            )
          case other => other
        }

    val outcome = GuiNotationApi(warningFacade).importFen(InitialFen)
    val success = outcome.asInstanceOf[GuiNotationOutcome.ImportSuccess]
    success.warnings should have size 3

    val categories = success.warnings.map(_.category)
    categories should contain(GuiWarningCategory.Informational)
    categories should contain(GuiWarningCategory.DataLoss)
    categories should contain(GuiWarningCategory.Normalization)

    success.warnings.foreach(_.message should not be empty)
  }

  it should "translate IgnoredField notation warning to Informational" in {
    val ignoreFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        chess.notation.fen.FenParser.parse(InitialFen)
      def executeImport(
          parsed: ParsedNotation,
          target: ImportTarget
      ): Either[NotationFailure, ImportResult[GameState]] =
        chess.notation.fen.FenImporter.importNotation(parsed, target).map {
          case r: ImportResult.PositionImportResult[GameState @unchecked] =>
            r.copy(warnings = List(NotationWarning.IgnoredField("clock", "not supported")))
          case other => other
        }

    val outcome = GuiNotationApi(ignoreFacade).importFen(InitialFen)
    val success = outcome.asInstanceOf[GuiNotationOutcome.ImportSuccess]
    success.warnings.head.category shouldBe GuiWarningCategory.Informational
    success.warnings.head.message should include("clock")
  }

  // ── toImportSuccess: GameImportResult branch ──────────────────────────────

  "GuiNotationApi" should "return ImportSuccess from a GameImportResult payload" in {
    val gameResultFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        chess.notation.fen.FenParser.parse(InitialFen)
      def executeImport(
          parsed: ParsedNotation,
          target: ImportTarget
      ): Either[NotationFailure, ImportResult[GameState]] =
        Right(
          ImportResult.GameImportResult(
            data = freshState,
            sourceFormat = NotationFormat.FEN,
            metadata = GameImportMetadata()
          )
        )

    val outcome = GuiNotationApi(gameResultFacade).importFen(InitialFen)
    outcome shouldBe a[GuiNotationOutcome.ImportSuccess]
    outcome.asInstanceOf[GuiNotationOutcome.ImportSuccess].state shouldBe freshState
  }

  it should "map warnings from a GameImportResult to GuiNotationWarning" in {
    val gameResultFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        chess.notation.fen.FenParser.parse(InitialFen)
      def executeImport(
          parsed: ParsedNotation,
          target: ImportTarget
      ): Either[NotationFailure, ImportResult[GameState]] =
        Right(
          ImportResult.GameImportResult(
            data = freshState,
            sourceFormat = NotationFormat.FEN,
            metadata = GameImportMetadata(),
            warnings = List(NotationWarning.UnknownTag("Event"))
          )
        )

    val outcome = GuiNotationApi(gameResultFacade).importFen(InitialFen)
    val success = outcome.asInstanceOf[GuiNotationOutcome.ImportSuccess]
    success.warnings should have size 1
    success.warnings.head.category shouldBe GuiWarningCategory.Informational
  }

  // ── toGuiWarning: GenericWarning branch ───────────────────────────────────

  it should "translate GenericWarning to Informational" in {
    val genericWarnFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        chess.notation.fen.FenParser.parse(InitialFen)
      def executeImport(
          parsed: ParsedNotation,
          target: ImportTarget
      ): Either[NotationFailure, ImportResult[GameState]] =
        chess.notation.fen.FenImporter.importNotation(parsed, target).map {
          case r: ImportResult.PositionImportResult[GameState @unchecked] =>
            r.copy(warnings = List(NotationWarning.GenericWarning("unusual but harmless")))
          case other => other
        }

    val outcome = GuiNotationApi(genericWarnFacade).importFen(InitialFen)
    val success = outcome.asInstanceOf[GuiNotationOutcome.ImportSuccess]
    success.warnings.head.category shouldBe GuiWarningCategory.Informational
    success.warnings.head.message shouldBe "unusual but harmless"
  }

  // ── toFailure: SyntaxError with line-only (no column) ─────────────────────

  it should "produce details 'Line N' for SyntaxError with line but no column" in {
    val lineOnlyFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        Left(ParseFailure.SyntaxError("unexpected token", line = Some(3), column = None))
      def executeImport(
          parsed: ParsedNotation,
          target: ImportTarget
      ): Either[NotationFailure, ImportResult[GameState]] =
        Left(ImportFailure.MappingError("unreachable"))

    val outcome = GuiNotationApi(lineOnlyFacade).importFen("x")
    val failure = outcome.asInstanceOf[GuiNotationOutcome.Failure]
    failure.category shouldBe FailureCategory.InvalidInput
    failure.details shouldBe Some("Line 3")
  }

  it should "produce no details for SyntaxError with neither line nor column" in {
    val noLocationFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        Left(ParseFailure.SyntaxError("bad token", line = None, column = None))
      def executeImport(
          parsed: ParsedNotation,
          target: ImportTarget
      ): Either[NotationFailure, ImportResult[GameState]] =
        Left(ImportFailure.MappingError("unreachable"))

    val outcome = GuiNotationApi(noLocationFacade).importFen("x")
    val failure = outcome.asInstanceOf[GuiNotationOutcome.Failure]
    failure.category shouldBe FailureCategory.InvalidInput
    failure.details shouldBe None
  }

  // ── toFailure: ImportFailure branch ───────────────────────────────────────

  it should "map ImportFailure.MappingError to Failure(SemanticError)" in {
    val mappingErrorFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        chess.notation.fen.FenParser.parse(InitialFen)
      def executeImport(
          parsed: ParsedNotation,
          target: ImportTarget
      ): Either[NotationFailure, ImportResult[GameState]] =
        Left(ImportFailure.MappingError("could not map field X"))

    val outcome = GuiNotationApi(mappingErrorFacade).importFen(InitialFen)
    val failure = outcome.asInstanceOf[GuiNotationOutcome.Failure]
    failure.category shouldBe FailureCategory.SemanticError
    failure.message should include("could not map field X")
  }

  it should "map ImportFailure.IncompatibleTarget to Failure(SemanticError)" in {
    import chess.notation.api.ParsedNotationKind
    val incompatibleFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        chess.notation.fen.FenParser.parse(InitialFen)
      def executeImport(
          parsed: ParsedNotation,
          target: ImportTarget
      ): Either[NotationFailure, ImportResult[GameState]] =
        Left(
          ImportFailure.IncompatibleTarget(
            parsedKind = ParsedNotationKind.Fen,
            target = ImportTarget.GameTarget,
            message = "position notation cannot satisfy a game target"
          )
        )

    val outcome = GuiNotationApi(incompatibleFacade).importFen(InitialFen)
    val failure = outcome.asInstanceOf[GuiNotationOutcome.Failure]
    failure.category shouldBe FailureCategory.SemanticError
    failure.message should include("game target")
  }

  // ── toFailure: ExportFailure branches (via exportFen) ─────────────────────

  it should "map ExportFailure.UnsupportedExportFormat to Failure(UnavailableFeature)" in {
    val unsupportedExportFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        Left(ParseFailure.StructuralError("stub"))
      def executeImport(
          parsed: ParsedNotation,
          target: ImportTarget
      ): Either[NotationFailure, ImportResult[GameState]] =
        Left(ImportFailure.MappingError("stub"))
      override def executeExport(
          data: GameState,
          format: NotationFormat
      ): Either[NotationFailure, ExportResult] =
        Left(ExportFailure.UnsupportedExportFormat(format, "FEN export not implemented"))

    val outcome = GuiNotationApi(unsupportedExportFacade).exportFen(freshState)
    val failure = outcome.asInstanceOf[GuiNotationOutcome.Failure]
    failure.category shouldBe FailureCategory.UnavailableFeature
    failure.message should include("not implemented")
  }

  it should "map ExportFailure.SerializationError to Failure(SemanticError)" in {
    val serializationErrorFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        Left(ParseFailure.StructuralError("stub"))
      def executeImport(
          parsed: ParsedNotation,
          target: ImportTarget
      ): Either[NotationFailure, ImportResult[GameState]] =
        Left(ImportFailure.MappingError("stub"))
      override def executeExport(
          data: GameState,
          format: NotationFormat
      ): Either[NotationFailure, ExportResult] =
        Left(ExportFailure.SerializationError("halfmoveClock", "value out of representable range"))

    val outcome = GuiNotationApi(serializationErrorFacade).exportFen(freshState)
    val failure = outcome.asInstanceOf[GuiNotationOutcome.Failure]
    failure.category shouldBe FailureCategory.SemanticError
    failure.message should include("out of representable range")
  }

  // ── No notation-layer leakage ──────────────────────────────────────────────

  "GuiNotationApi" should "return only GuiNotationOutcome subtypes, never raw notation-layer ADTs" in {
    val outcomes: List[GuiNotationOutcome] = List(
      GuiNotationApi.importFen(InitialFen),
      GuiNotationApi.importFen("bad"),
      GuiNotationApi.importPgn("1. e4 e5 *"),
      GuiNotationApi.exportFen(freshState),
      GuiNotationApi.exportPgn(freshState)
    )
    all(outcomes) shouldBe a[GuiNotationOutcome]
    outcomes
      .collect { case f: GuiNotationOutcome.Failure => f }
      .foreach(_.message should not be empty)
  }
