package chess.notation.api

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues

/** Verifies the notation export API contracts:
  *   - [[ExportResult]] structure and consistency
  *   - [[NotationExporter]] can be stubbed and invoked
  *   - [[NotationFacade.executeExport]] default and override behaviour
  *   - [[ExportFailure]] hierarchy structure
  */
class NotationExportContractSpec extends AnyFlatSpec with Matchers with EitherValues:

  // ── ExportResult ────────────────────────────────────────────────────────────

  "ExportResult" should "carry text, format and empty warnings for a clean export" in {
    val result = ExportResult(
      text = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
      format = NotationFormat.FEN
    )
    result.text should not be empty
    result.format shouldBe NotationFormat.FEN
    result.warnings shouldBe Nil
  }

  it should "carry structured warnings when the export was lossy" in {
    val result = ExportResult(
      text = "some-text",
      format = NotationFormat.PGN,
      warnings = List(NotationWarning.IgnoredField("clock", "PGN does not encode halfmove clock"))
    )
    result.warnings should have size 1
    result.warnings.head shouldBe a[NotationWarning.IgnoredField]
  }

  it should "support exhaustive pattern matching on format" in {
    def label(r: ExportResult): String = r.format match
      case NotationFormat.FEN  => "fen"
      case NotationFormat.PGN  => "pgn"
      case NotationFormat.JSON => "json"
    label(ExportResult("x", NotationFormat.FEN)) shouldBe "fen"
    label(ExportResult("x", NotationFormat.PGN)) shouldBe "pgn"
    label(ExportResult("x", NotationFormat.JSON)) shouldBe "json"
  }

  // ── ExportFailure hierarchy ─────────────────────────────────────────────────

  "ExportFailure" should "be a NotationFailure" in {
    val f: NotationFailure =
      ExportFailure.UnsupportedExportFormat(NotationFormat.PGN, "not implemented")
    f.message should include("not implemented")
  }

  it should "distinguish UnsupportedExportFormat from SerializationError" in {
    val unsupported: ExportFailure =
      ExportFailure.UnsupportedExportFormat(NotationFormat.JSON, "no JSON exporter")
    val serialErr: ExportFailure =
      ExportFailure.SerializationError("halfmoveClock", "value out of range")

    unsupported match
      case ExportFailure.UnsupportedExportFormat(fmt, _) => fmt shouldBe NotationFormat.JSON
      case _                                             => fail("expected UnsupportedExportFormat")

    serialErr match
      case ExportFailure.SerializationError(field, _) => field shouldBe "halfmoveClock"
      case _                                          => fail("expected SerializationError")
  }

  it should "carry the target format in UnsupportedExportFormat" in {
    val f =
      ExportFailure.UnsupportedExportFormat(NotationFormat.FEN, "FEN export not yet available")
    f.format shouldBe NotationFormat.FEN
    f.message should include("FEN export")
  }

  // ── NotationExporter stub ───────────────────────────────────────────────────

  /** Minimal stub that supports FEN export and rejects all other formats. */
  private object StubFenExporter extends NotationExporter[String]:
    def exportNotation(
        data: String,
        format: NotationFormat
    ): Either[NotationFailure, ExportResult] =
      format match
        case NotationFormat.FEN =>
          Right(ExportResult(text = s"FEN:$data", format = NotationFormat.FEN))
        case other =>
          Left(
            ExportFailure.UnsupportedExportFormat(other, s"$other not supported by StubFenExporter")
          )

  "NotationExporter (stub)" should "return Right(ExportResult) for a supported format" in {
    val result = StubFenExporter.exportNotation("position-data", NotationFormat.FEN)
    result.value.text shouldBe "FEN:position-data"
    result.value.format shouldBe NotationFormat.FEN
  }

  it should "return Left(ExportFailure) for an unsupported format" in {
    val result = StubFenExporter.exportNotation("position-data", NotationFormat.PGN)
    result.left.value shouldBe a[ExportFailure.UnsupportedExportFormat]
  }

  it should "carry the rejected format in the UnsupportedExportFormat failure" in {
    val result = StubFenExporter.exportNotation("data", NotationFormat.JSON)
    result.left.value
      .asInstanceOf[ExportFailure.UnsupportedExportFormat]
      .format shouldBe NotationFormat.JSON
  }

  // ── NotationFacade: default executeExport ───────────────────────────────────

  /** Stub facade that does not override executeExport (tests the default). */
  private object StubImportOnlyFacade extends NotationFacade[String]:
    def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
      Left(ParseFailure.StructuralError("stub"))
    def executeImport(
        parsed: ParsedNotation,
        target: ImportTarget
    ): Either[NotationFailure, ImportResult[String]] =
      Left(ImportFailure.MappingError("stub"))

  "NotationFacade.executeExport (default)" should "return UnsupportedExportFormat for every format" in {
    for format <- Seq(NotationFormat.FEN, NotationFormat.PGN, NotationFormat.JSON) do
      val result = StubImportOnlyFacade.executeExport("data", format)
      result.left.value shouldBe a[ExportFailure.UnsupportedExportFormat]
  }

  it should "include the requested format in the failure" in {
    val result = StubImportOnlyFacade.executeExport("data", NotationFormat.FEN)
    result.left.value
      .asInstanceOf[ExportFailure.UnsupportedExportFormat]
      .format shouldBe NotationFormat.FEN
  }

  // ── NotationFacade: executeExport override ──────────────────────────────────

  /** Stub facade that overrides executeExport to delegate to [[StubFenExporter]]. */
  private object StubExportAwareFacade extends NotationFacade[String]:
    def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
      Left(ParseFailure.StructuralError("stub"))
    def executeImport(
        parsed: ParsedNotation,
        target: ImportTarget
    ): Either[NotationFailure, ImportResult[String]] =
      Left(ImportFailure.MappingError("stub"))
    override def executeExport(
        data: String,
        format: NotationFormat
    ): Either[NotationFailure, ExportResult] =
      StubFenExporter.exportNotation(data, format)

  "NotationFacade.executeExport (overridden)" should "succeed for a supported format" in {
    val result = StubExportAwareFacade.executeExport("my-position", NotationFormat.FEN)
    result.value.text shouldBe "FEN:my-position"
    result.value.format shouldBe NotationFormat.FEN
  }

  it should "still fail cleanly for an unsupported format via the exporter" in {
    val result = StubExportAwareFacade.executeExport("my-position", NotationFormat.PGN)
    result.left.value shouldBe a[ExportFailure.UnsupportedExportFormat]
  }

  // ── ExportFailure is a NotationFailure (shared hierarchy) ──────────────────

  "ExportFailure" should "be usable wherever NotationFailure is expected" in {
    val failures: List[NotationFailure] = List(
      ExportFailure.UnsupportedExportFormat(NotationFormat.FEN, "not implemented"),
      ExportFailure.SerializationError("rank", "invalid rank value"),
      ParseFailure.SyntaxError("bad token"),
      ImportFailure.MappingError("unmapped field")
    )
    failures.map(_.message) should contain allOf (
      "not implemented",
      "invalid rank value"
    )
  }
