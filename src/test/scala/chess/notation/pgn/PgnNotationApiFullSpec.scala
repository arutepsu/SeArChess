package chess.notation.pgn

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.notation.api.{PgnImportResult, PgnExportResult, ImportResult, NotationWarning}

class PgnNotationApiFullSpec extends AnyFlatSpec with Matchers {
  "PgnNotationApi.importPgn" should "return Success for valid PGN" in {
    val pgn = "1. e4 e5 2. Nf3 Nc6"
    val result = PgnNotationApi.importPgn(pgn)
    result shouldBe a [PgnImportResult.Success]
    result.asInstanceOf[PgnImportResult.Success].moves should contain ("e4")
  }

  it should "return Failure for empty PGN" in {
    val result = PgnNotationApi.importPgn("")
    result shouldBe a [PgnImportResult.Failure]
  }

  "PgnNotationApi.exportPgn" should "return Success for move list" in {
    val moves = List("e4", "e5", "Nf3", "Nc6")
    val result = PgnNotationApi.exportPgn(moves)
    result shouldBe a [PgnExportResult.Success]
    result.asInstanceOf[PgnExportResult.Success].pgn should include ("e4")
  }

  it should "return Failure for exporter exception" in {
    val moves = null.asInstanceOf[List[String]]
    val result = PgnNotationApi.exportPgn(moves)
    result shouldBe a [PgnExportResult.Failure]
  }

  "PgnNotationExporter.exportToString" should "wrap export result" in {
    val moves = List("e4", "e5")
    val result = PgnNotationExporter.exportToString(moves)
    result.text should include ("e4")
    result.format.toString shouldBe "PGN"
  }

  it should "wrap failure with warning" in {
    val moves = null.asInstanceOf[List[String]]
    val result = PgnNotationExporter.exportToString(moves)
    result.text shouldBe ""
    result.warnings.exists(_.isInstanceOf[NotationWarning.IgnoredField]) shouldBe true
  }

  "PgnNotationImporter.importFromString" should "wrap import result" in {
    val pgn = "1. e4 e5"
    val result = PgnNotationImporter.importFromString(pgn)
    result match {
      case ImportResult.GameImportResult(data, sourceFormat, _, _, _) =>
        data should contain ("e4")
        sourceFormat.toString shouldBe "PGN"
      case _ => fail("Expected GameImportResult")
    }
  }

  it should "wrap failure with warning and empty data" in {
    val result = PgnNotationImporter.importFromString("")
    result match {
      case ImportResult.GameImportResult(data, _, _, _, warnings) =>
        data shouldBe Nil
        warnings.exists(_.isInstanceOf[NotationWarning.IgnoredField]) shouldBe true
      case _ => fail("Expected GameImportResult")
    }
  }

  "PgnParser.parseMoves" should "parse moves from PGN string" in {
    val pgn = "1. e4 e5 2. Nf3 Nc6"
    val result = PgnParser.parseMoves(pgn)
    result shouldBe Right(List("e4", "e5", "Nf3", "Nc6"))
  }

  it should "return Left for empty PGN" in {
    val result = PgnParser.parseMoves("")
    result shouldBe Left("No moves found in PGN")
  }
}
