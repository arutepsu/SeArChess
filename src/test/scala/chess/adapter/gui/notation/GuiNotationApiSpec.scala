package chess.adapter.gui.notation

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.application.ChessService
import chess.domain.model.{Color, PieceType}
import chess.domain.state.GameState
import chess.notation.api.{
  CompatibilityFailure, ImportResult, ImportTarget, NotationFacade,
  NotationFailure, NotationFormat, NotationWarning, ParsedNotation,
  ParseFailure, PositionImportMetadata
}

/** Tests for [[GuiNotationApi]] at the GUI API boundary.
 *
 *  All assertions against outcomes use [[GuiNotationOutcome]], [[FailureCategory]],
 *  [[GuiNotationWarning]], and [[GuiWarningCategory]] only.
 *
 *  Notation-layer ADTs (NotationFailure, ImportResult, NotationFormat, …)
 *  appear ONLY in stub facades within this spec, confirming that those
 *  contracts are confined to the API implementation and not visible to callers.
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
    val outcome = GuiNotationApi.importFen(InitialFen).asInstanceOf[GuiNotationOutcome.ImportSuccess]
    outcome.state.currentPlayer shouldBe Color.White
  }

  it should "carry no warnings for a clean standard FEN" in {
    val outcome = GuiNotationApi.importFen(InitialFen).asInstanceOf[GuiNotationOutcome.ImportSuccess]
    outcome.warnings shouldBe Nil
  }

  it should "expose warnings as List[GuiNotationWarning], not List[String]" in {
    val outcome   = GuiNotationApi.importFen(InitialFen).asInstanceOf[GuiNotationOutcome.ImportSuccess]
    // Assigning to a typed val is a compile-time proof that warnings is List[GuiNotationWarning].
    val ws: List[GuiNotationWarning] = outcome.warnings
    ws shouldBe Nil
  }

  it should "return a GameState whose board matches the starting position" in {
    import chess.domain.model.{Piece, Position}
    val outcome = GuiNotationApi.importFen(InitialFen).asInstanceOf[GuiNotationOutcome.ImportSuccess]
    val e2 = Position.fromAlgebraic("e2").getOrElse(throw AssertionError("bad pos"))
    outcome.state.board.pieceAt(e2) shouldBe Some(Piece(Color.White, PieceType.Pawn))
  }

  it should "respect the active color encoded in the FEN" in {
    val blackToMoveFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
    val outcome = GuiNotationApi.importFen(blackToMoveFen).asInstanceOf[GuiNotationOutcome.ImportSuccess]
    outcome.state.currentPlayer shouldBe Color.Black
  }

  it should "thread halfmoveClock from the FEN into the GameState" in {
    val fenWith5Clock = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 5 1"
    val outcome = GuiNotationApi.importFen(fenWith5Clock).asInstanceOf[GuiNotationOutcome.ImportSuccess]
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

  // ── PGN import: unavailable ────────────────────────────────────────────────

  "GuiNotationApi.importPgn" should "return Failure(UnavailableFeature) without crashing" in {
    val outcome = GuiNotationApi.importPgn("1. e4 e5 2. Nf3 Nc6 *")
    outcome shouldBe a[GuiNotationOutcome.Failure]
    outcome.asInstanceOf[GuiNotationOutcome.Failure].category shouldBe FailureCategory.UnavailableFeature
  }

  it should "include a non-empty message explaining the feature is unavailable" in {
    val outcome = GuiNotationApi.importPgn("any pgn").asInstanceOf[GuiNotationOutcome.Failure]
    outcome.message should not be empty
  }

  // ── FEN export: unavailable ────────────────────────────────────────────────

  "GuiNotationApi.exportFen" should "return Failure(UnavailableFeature) without crashing" in {
    val outcome = GuiNotationApi.exportFen(freshState)
    outcome shouldBe a[GuiNotationOutcome.Failure]
    outcome.asInstanceOf[GuiNotationOutcome.Failure].category shouldBe FailureCategory.UnavailableFeature
  }

  it should "include a non-empty message explaining the feature is unavailable" in {
    val outcome = GuiNotationApi.exportFen(freshState).asInstanceOf[GuiNotationOutcome.Failure]
    outcome.message should not be empty
  }

  // ── PGN export: unavailable ────────────────────────────────────────────────

  "GuiNotationApi.exportPgn" should "return Failure(UnavailableFeature) without crashing" in {
    val outcome = GuiNotationApi.exportPgn(freshState)
    outcome shouldBe a[GuiNotationOutcome.Failure]
    outcome.asInstanceOf[GuiNotationOutcome.Failure].category shouldBe FailureCategory.UnavailableFeature
  }

  it should "include a non-empty message explaining the feature is unavailable" in {
    val outcome = GuiNotationApi.exportPgn(freshState).asInstanceOf[GuiNotationOutcome.Failure]
    outcome.message should not be empty
  }

  // ── Dependency injection / wiring boundary ─────────────────────────────────

  "GuiNotationApi (injected facade)" should "use the provided facade for FEN import" in {
    // Verify that a custom-injected facade is actually called, not the default.
    var called = false
    val stubFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        called = true
        Left(ParseFailure.StructuralError("stub"))
      def executeImport(parsed: ParsedNotation, target: ImportTarget): Either[NotationFailure, ImportResult[GameState]] =
        Left(chess.notation.api.ImportFailure.MappingError("unreachable"))

    val api = GuiNotationApi(stubFacade)
    api.importFen("anything")
    called shouldBe true
  }

  it should "return Failure(InvalidInput) when the injected facade returns a parse failure" in {
    val failingFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        Left(ParseFailure.SyntaxError("stub syntax error", line = Some(1), column = Some(5)))
      def executeImport(parsed: ParsedNotation, target: ImportTarget): Either[NotationFailure, ImportResult[GameState]] =
        Left(chess.notation.api.ImportFailure.MappingError("unreachable"))

    val outcome = GuiNotationApi(failingFacade).importFen("x")
    val failure = outcome.asInstanceOf[GuiNotationOutcome.Failure]
    failure.category shouldBe FailureCategory.InvalidInput
    failure.details  shouldBe Some("Line 1, column 5")
  }

  // ── UnsupportedInput vs UnavailableFeature ─────────────────────────────────

  "GuiNotationApi" should "map CompatibilityFailure to Failure(UnsupportedInput)" in {
    val dialectFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        Left(ParseFailure.StructuralError("parsed"))
      def executeImport(parsed: ParsedNotation, target: ImportTarget): Either[NotationFailure, ImportResult[GameState]] =
        Left(CompatibilityFailure.UnsupportedDialect("Chess960", "not supported"))

    // parseAndImport short-circuits at parse stage, so wire a facade that gets to executeImport
    val dialectFacade2 = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        chess.notation.fen.FenParser.parse(InitialFen)   // parse succeeds
      def executeImport(parsed: ParsedNotation, target: ImportTarget): Either[NotationFailure, ImportResult[GameState]] =
        Left(CompatibilityFailure.UnsupportedDialect("Chess960", "Chess960 is not supported"))

    val outcome = GuiNotationApi(dialectFacade2).importFen(InitialFen)
    val failure = outcome.asInstanceOf[GuiNotationOutcome.Failure]
    failure.category shouldBe FailureCategory.UnsupportedInput
  }

  it should "map not-implemented operations to Failure(UnavailableFeature)" in {
    // importPgn / exportFen / exportPgn all return UnavailableFeature
    val api = GuiNotationApi.default
    List(
      api.importPgn("1. e4 *"),
      api.exportFen(freshState),
      api.exportPgn(freshState)
    ).foreach { outcome =>
      outcome.asInstanceOf[GuiNotationOutcome.Failure].category shouldBe FailureCategory.UnavailableFeature
    }
  }

  // ── Structured warnings ────────────────────────────────────────────────────

  "GuiNotationApi" should "translate notation-layer warnings to GuiNotationWarning with correct category" in {
    val warningFacade = new NotationFacade[GameState]:
      def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
        chess.notation.fen.FenParser.parse(InitialFen)
      def executeImport(parsed: ParsedNotation, target: ImportTarget): Either[NotationFailure, ImportResult[GameState]] =
        chess.notation.fen.FenImporter.importNotation(parsed, target).map {
          case r: ImportResult.PositionImportResult[GameState @unchecked] =>
            r.copy(warnings = List(
              NotationWarning.UnknownTag("Annotator"),
              NotationWarning.UnsupportedExtensionIgnored("NAG-7"),
              NotationWarning.NormalizationApplied("filled missing result token")
            ))
          case other => other
        }

    val outcome  = GuiNotationApi(warningFacade).importFen(InitialFen)
    val success  = outcome.asInstanceOf[GuiNotationOutcome.ImportSuccess]
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
      def executeImport(parsed: ParsedNotation, target: ImportTarget): Either[NotationFailure, ImportResult[GameState]] =
        chess.notation.fen.FenImporter.importNotation(parsed, target).map {
          case r: ImportResult.PositionImportResult[GameState @unchecked] =>
            r.copy(warnings = List(NotationWarning.IgnoredField("clock", "not supported")))
          case other => other
        }

    val outcome = GuiNotationApi(ignoreFacade).importFen(InitialFen)
    val success = outcome.asInstanceOf[GuiNotationOutcome.ImportSuccess]
    success.warnings.head.category shouldBe GuiWarningCategory.Informational
    success.warnings.head.message  should include("clock")
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
    outcomes.collect { case f: GuiNotationOutcome.Failure => f }
      .foreach(_.message should not be empty)
  }
