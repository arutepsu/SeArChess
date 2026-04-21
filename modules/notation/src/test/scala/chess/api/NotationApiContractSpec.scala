package chess.notation.api

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import org.scalatest.OptionValues

/** Verifies the notation API contracts:
  *   - ADT construction and pattern matching
  *   - stub parser implementing [[NotationParser]]
  *   - stub importer implementing [[NotationImporter]]
  *   - stub façade implementing [[NotationFacade]]
  *   - failure hierarchy structure
  *   - warning hierarchy structure
  *   - import result semantics (sourceFormat, metadata, replay, warnings)
  *
  * No real parsing logic is exercised here.
  */
class NotationApiContractSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  // A well-formed FEN used wherever a valid ParsedFen instance is needed.
  private val InitialFenStr = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  /** Return a real [[ParsedNotation.ParsedFen]] for the initial position. */
  private def aValidParsedFen: ParsedNotation.ParsedFen =
    chess.notation.fen.FenParser
      .parse(InitialFenStr)
      .value
      .asInstanceOf[ParsedNotation.ParsedFen]

  // ── NotationFormat ──────────────────────────────────────────────────────────

  "NotationFormat" should "enumerate all expected formats" in {
    val formats: Set[NotationFormat] =
      Set(NotationFormat.PGN, NotationFormat.FEN, NotationFormat.JSON)
    formats should have size 3
  }

  it should "support exhaustive pattern matching" in {
    def label(f: NotationFormat): String = f match
      case NotationFormat.PGN  => "pgn"
      case NotationFormat.FEN  => "fen"
      case NotationFormat.JSON => "json"
    label(NotationFormat.FEN) shouldBe "fen"
    label(NotationFormat.PGN) shouldBe "pgn"
    label(NotationFormat.JSON) shouldBe "json"
  }

  // ── ParsedNotation ──────────────────────────────────────────────────────────

  "ParsedNotation" should "hold raw input in every variant" in {
    val fen = aValidParsedFen
    val pgn =
      ParsedNotation.ParsedPgn("...", PgnData(Map("Event" -> "Test"), Vector("e4", "e5"), None))
    val pos = ParsedNotation.ParsedJsonPosition("""{"type":"position"}""")
    val game = ParsedNotation.ParsedJsonGame("""{"type":"game"}""")
    fen.raw should not be empty
    pgn.raw should not be empty
    pos.raw should not be empty
    game.raw should not be empty
  }

  it should "support exhaustive pattern matching" in {
    def describe(n: ParsedNotation): String = n match
      case _: ParsedNotation.ParsedFen          => "fen"
      case _: ParsedNotation.ParsedPgn          => "pgn"
      case _: ParsedNotation.ParsedJsonPosition => "json-position"
      case _: ParsedNotation.ParsedJsonGame     => "json-game"
    describe(aValidParsedFen) shouldBe "fen"
    describe(ParsedNotation.ParsedPgn("x", PgnData(Map.empty, Vector.empty, None))) shouldBe "pgn"
    describe(ParsedNotation.ParsedJsonPosition("x")) shouldBe "json-position"
    describe(ParsedNotation.ParsedJsonGame("x")) shouldBe "json-game"
  }

  it should "carry PGN headers, move tokens, and result in PgnData" in {
    val pgn = ParsedNotation.ParsedPgn(
      raw = "[White \"Alice\"]\n1. e4 e5 *",
      data = PgnData(
        headers = Map("White" -> "Alice"),
        moveTokens = Vector("e4", "e5"),
        result = Some("*")
      )
    )
    pgn.data.headers("White") shouldBe "Alice"
    pgn.data.moveTokens shouldBe Vector("e4", "e5")
    pgn.data.result shouldBe Some("*")
  }

  it should "expose a kind discriminator matching the variant" in {
    aValidParsedFen.kind shouldBe ParsedNotationKind.Fen
    ParsedNotation
      .ParsedPgn("x", PgnData(Map.empty, Vector.empty, None))
      .kind shouldBe ParsedNotationKind.Pgn
    ParsedNotation.ParsedJsonPosition("x").kind shouldBe ParsedNotationKind.JsonPosition
    ParsedNotation.ParsedJsonGame("x").kind shouldBe ParsedNotationKind.JsonGame
  }

  // ── ParsedNotationKind ──────────────────────────────────────────────────────

  "ParsedNotationKind" should "enumerate all four variants" in {
    val kinds: Set[ParsedNotationKind] =
      Set(
        ParsedNotationKind.Fen,
        ParsedNotationKind.Pgn,
        ParsedNotationKind.JsonPosition,
        ParsedNotationKind.JsonGame
      )
    kinds should have size 4
  }

  // ── Failure hierarchy ───────────────────────────────────────────────────────

  "ParseFailure" should "be a NotationFailure" in {
    val f: NotationFailure =
      ParseFailure.SyntaxError("unexpected token", line = Some(3), column = Some(7))
    f.message should include("unexpected token")
  }

  it should "support all variants" in {
    val syntax: ParseFailure = ParseFailure.SyntaxError("bad", Some(1), Some(1))
    val struct: ParseFailure = ParseFailure.StructuralError("incomplete")
    val eoi: ParseFailure = ParseFailure.UnexpectedEndOfInput("truncated")
    List(syntax, struct, eoi).map(_.message) should contain allOf ("bad", "incomplete", "truncated")
  }

  "ValidationFailure" should "be a NotationFailure with structured fields" in {
    val f = ValidationFailure.InvalidValue("rank", "9", "rank must be 1–8")
    f.field shouldBe "rank"
    f.value shouldBe "9"
    f.message should include("1–8")
  }

  "ImportFailure.IncompatibleTarget" should "use typed parsedKind and target" in {
    val f = ImportFailure.IncompatibleTarget(
      parsedKind = ParsedNotationKind.Fen,
      target = ImportTarget.GameTarget,
      message = "FEN encodes a position, not a game"
    )
    f.parsedKind shouldBe ParsedNotationKind.Fen
    f.target shouldBe ImportTarget.GameTarget
    f.message should include("position")
  }

  "CompatibilityFailure" should "carry dialect and version variants" in {
    val d = CompatibilityFailure.UnsupportedDialect("Chess960", "not yet supported")
    val v = CompatibilityFailure.UnsupportedVersion("2.1", "only version 1.x supported")
    d.dialect shouldBe "Chess960"
    v.version shouldBe "2.1"
  }

  // ── NotationWarning ─────────────────────────────────────────────────────────

  "NotationWarning.UnknownTag" should "include the tag name in its message" in {
    val w = NotationWarning.UnknownTag("Annotator")
    w.message should include("Annotator")
  }

  "NotationWarning.IgnoredField" should "include field and reason" in {
    val w = NotationWarning.IgnoredField("clock", "clock data not supported")
    w.message should include("clock")
    w.message should include("not supported")
  }

  "NotationWarning.UnsupportedExtensionIgnored" should "include extension name" in {
    val w = NotationWarning.UnsupportedExtensionIgnored("NAG-7")
    w.message should include("NAG-7")
  }

  "NotationWarning.NormalizationApplied" should "expose the description as its message" in {
    val w = NotationWarning.NormalizationApplied("filled missing result token with *")
    w.message shouldBe "filled missing result token with *"
  }

  "NotationWarning" should "support exhaustive pattern matching" in {
    def kind(w: NotationWarning): String = w match
      case _: NotationWarning.UnknownTag                  => "unknown-tag"
      case _: NotationWarning.IgnoredField                => "ignored-field"
      case _: NotationWarning.UnsupportedExtensionIgnored => "unsupported-ext"
      case _: NotationWarning.NormalizationApplied        => "normalized"
      case _: NotationWarning.GenericWarning              => "generic"
    kind(NotationWarning.UnknownTag("x")) shouldBe "unknown-tag"
    kind(NotationWarning.IgnoredField("x", "y")) shouldBe "ignored-field"
    kind(NotationWarning.UnsupportedExtensionIgnored("x")) shouldBe "unsupported-ext"
    kind(NotationWarning.NormalizationApplied("x")) shouldBe "normalized"
    kind(NotationWarning.GenericWarning("x")) shouldBe "generic"
  }

  // ── ImportTarget ────────────────────────────────────────────────────────────

  "ImportTarget" should "support exhaustive pattern matching" in {
    def name(t: ImportTarget): String = t match
      case ImportTarget.PositionTarget => "position"
      case ImportTarget.GameTarget     => "game"
    name(ImportTarget.PositionTarget) shouldBe "position"
    name(ImportTarget.GameTarget) shouldBe "game"
  }

  // ── PositionImportMetadata ──────────────────────────────────────────────────

  "PositionImportMetadata" should "default to no normalization, no dialect, version, or clock fields" in {
    val m = PositionImportMetadata()
    m.normalized shouldBe false
    m.sourceDialect shouldBe None
    m.sourceVersion shouldBe None
    m.halfmoveClock shouldBe None
    m.fullmoveNumber shouldBe None
  }

  it should "record optional dialect and version" in {
    val m = PositionImportMetadata(
      normalized = true,
      sourceDialect = Some("Chess960"),
      sourceVersion = Some("1.0")
    )
    m.normalized shouldBe true
    m.sourceDialect shouldBe Some("Chess960")
    m.sourceVersion shouldBe Some("1.0")
  }

  it should "record optional clock fields" in {
    val m = PositionImportMetadata(halfmoveClock = Some(3), fullmoveNumber = Some(7))
    m.halfmoveClock shouldBe Some(3)
    m.fullmoveNumber shouldBe Some(7)
  }

  // ── GameImportMetadata ──────────────────────────────────────────────────────

  "GameImportMetadata" should "default to false for all boolean flags" in {
    val m = GameImportMetadata()
    m.normalized shouldBe false
    m.hasStartingPositionOverride shouldBe false
    m.sourceDialect shouldBe None
  }

  it should "record a starting-position override" in {
    val m = GameImportMetadata(hasStartingPositionOverride = true)
    m.hasStartingPositionOverride shouldBe true
  }

  // ── ReplaySummary ───────────────────────────────────────────────────────────

  "ReplaySummary" should "default to full replay with no move count or position override" in {
    val r = ReplaySummary()
    r.moveCount shouldBe None
    r.isFullReplay shouldBe true
    r.hasStartingPositionOverride shouldBe false
  }

  it should "carry a move count when provided" in {
    val r = ReplaySummary(moveCount = Some(80), isFullReplay = true)
    r.moveCount.value shouldBe 80
  }

  it should "record a partial replay" in {
    val r = ReplaySummary(moveCount = Some(30), isFullReplay = false)
    r.isFullReplay shouldBe false
  }

  // ── ImportResult ────────────────────────────────────────────────────────────

  "ImportResult.PositionImportResult" should "carry data, sourceFormat, metadata and warnings" in {
    val pos = ImportResult.PositionImportResult(
      data = "board-placeholder",
      sourceFormat = NotationFormat.FEN,
      metadata = PositionImportMetadata(normalized = true),
      warnings = List(NotationWarning.UnknownTag("Annotator"))
    )
    pos.data shouldBe "board-placeholder"
    pos.sourceFormat shouldBe NotationFormat.FEN
    pos.metadata.normalized shouldBe true
    pos.warnings shouldBe List(NotationWarning.UnknownTag("Annotator"))
  }

  "ImportResult.GameImportResult" should "carry data, sourceFormat, metadata, optional replay and warnings" in {
    val game = ImportResult.GameImportResult(
      data = 42,
      sourceFormat = NotationFormat.PGN,
      metadata = GameImportMetadata(hasStartingPositionOverride = false),
      replay = Some(ReplaySummary(moveCount = Some(40))),
      warnings = Nil
    )
    game.data shouldBe 42
    game.sourceFormat shouldBe NotationFormat.PGN
    game.replay.value.moveCount shouldBe Some(40)
    game.warnings shouldBe Nil
  }

  it should "allow absent replay summary" in {
    val game = ImportResult.GameImportResult(
      data = "game-data",
      sourceFormat = NotationFormat.JSON,
      metadata = GameImportMetadata()
    )
    game.replay shouldBe None
  }

  it should "default to empty warnings" in {
    val game = ImportResult.GameImportResult(
      data = "game-data",
      sourceFormat = NotationFormat.PGN,
      metadata = GameImportMetadata()
    )
    game.warnings shouldBe Nil
  }

  "ImportResult" should "be accessible via the sealed trait" in {
    val result: ImportResult[String] = ImportResult.PositionImportResult(
      data = "x",
      sourceFormat = NotationFormat.FEN,
      metadata = PositionImportMetadata()
    )
    result.sourceFormat shouldBe NotationFormat.FEN
    result.warnings shouldBe Nil
  }

  // ── Stub NotationParser ─────────────────────────────────────────────────────

  /** Minimal stub that always succeeds, returning a fixed ParsedFen. */
  private object StubFenParser extends NotationParser:
    val format: NotationFormat = NotationFormat.FEN
    def parse(input: String): Either[ParseFailure, ParsedNotation] =
      if input.isEmpty then Left(ParseFailure.UnexpectedEndOfInput("empty input"))
      else chess.notation.fen.FenParser.parse(input)

  "NotationParser (stub)" should "declare its format" in {
    StubFenParser.format shouldBe NotationFormat.FEN
  }

  it should "return Right(ParsedFen) for non-empty input" in {
    val result = StubFenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    result.value shouldBe a[ParsedNotation.ParsedFen]
  }

  it should "return Left(ParseFailure) for empty input" in {
    val result = StubFenParser.parse("")
    result.left.value shouldBe a[ParseFailure.UnexpectedEndOfInput]
  }

  // ── Stub NotationImporter ───────────────────────────────────────────────────

  /** Minimal stub that accepts ParsedFen + PositionTarget and rejects everything else. */
  private object StubFenImporter extends NotationImporter[String]:
    def importNotation(
        parsed: ParsedNotation,
        target: ImportTarget
    ): Either[NotationFailure, ImportResult[String]] =
      (parsed, target) match
        case (fen: ParsedNotation.ParsedFen, ImportTarget.PositionTarget) =>
          Right(
            ImportResult.PositionImportResult(
              data = s"imported:${fen.raw}",
              sourceFormat = NotationFormat.FEN,
              metadata = PositionImportMetadata()
            )
          )
        case (_, ImportTarget.GameTarget) =>
          Left(ImportFailure.IncompatibleTarget(parsed.kind, ImportTarget.GameTarget, "not a game"))
        case _ =>
          Left(ImportFailure.MappingError("unsupported combination"))

  "NotationImporter (stub)" should "return Right for a compatible parsed/target pair" in {
    val fen = aValidParsedFen
    val result = StubFenImporter.importNotation(fen, ImportTarget.PositionTarget)
    result.value shouldBe a[ImportResult.PositionImportResult[?]]
    result.value
      .asInstanceOf[ImportResult.PositionImportResult[String]]
      .data shouldBe s"imported:$InitialFenStr"
  }

  it should "return Left(ImportFailure) for an incompatible target" in {
    val fen = aValidParsedFen
    val result = StubFenImporter.importNotation(fen, ImportTarget.GameTarget)
    result.left.value shouldBe a[ImportFailure.IncompatibleTarget]
  }

  it should "carry the correct parsedKind in IncompatibleTarget" in {
    val fen = aValidParsedFen
    val result = StubFenImporter.importNotation(fen, ImportTarget.GameTarget)
    result.left.value
      .asInstanceOf[ImportFailure.IncompatibleTarget]
      .parsedKind shouldBe ParsedNotationKind.Fen
  }

  // ── Stub NotationFacade ─────────────────────────────────────────────────────

  private object StubFacade extends NotationFacade[String]:
    def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
      format match
        case NotationFormat.FEN => StubFenParser.parse(input)
        case NotationFormat.PGN =>
          Left(ParseFailure.StructuralError("PGN parser not yet implemented"))
        case NotationFormat.JSON =>
          Left(ParseFailure.StructuralError("JSON parser not yet implemented"))

    def executeImport(
        parsed: ParsedNotation,
        target: ImportTarget
    ): Either[NotationFailure, ImportResult[String]] =
      StubFenImporter.importNotation(parsed, target)

  "NotationFacade (stub)" should "succeed for FEN + PositionTarget via parseAndImport" in {
    val result = StubFacade.parseAndImport(
      NotationFormat.FEN,
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
      ImportTarget.PositionTarget
    )
    result.value shouldBe a[ImportResult.PositionImportResult[?]]
  }

  it should "fail at parse stage for unimplemented format" in {
    val result = StubFacade.parseAndImport(NotationFormat.PGN, "1. e4 e5", ImportTarget.GameTarget)
    result.left.value shouldBe a[ParseFailure.StructuralError]
  }

  it should "fail at import stage for incompatible target" in {
    val result = StubFacade.parseAndImport(
      NotationFormat.FEN,
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
      ImportTarget.GameTarget
    )
    result.left.value shouldBe a[ImportFailure.IncompatibleTarget]
  }

  it should "inherit parseAndImport default implementation from the trait" in {
    val result = StubFacade.parseAndImport(NotationFormat.FEN, "", ImportTarget.PositionTarget)
    result.left.value shouldBe a[ParseFailure.UnexpectedEndOfInput]
  }
