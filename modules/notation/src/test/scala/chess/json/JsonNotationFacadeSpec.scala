package chess.notation.json

import org.scalatest.funsuite.AnyFunSuite
import chess.domain.state._
import chess.domain.model._
import chess.notation.api._

class JsonNotationFacadeSpec extends AnyFunSuite {

  import ujson._

  test("toJsonGameStatus serialisiert Checkmate und Draw korrekt") {
    val cm = GameStatus.Checkmate(Color.Black)
    val dr = GameStatus.Draw(DrawReason.Stalemate)
    val cmMeth =
      JsonNotationFacade.getClass.getDeclaredMethod("toJsonGameStatus", classOf[GameStatus])
    cmMeth.setAccessible(true)
    val cmJson = cmMeth.invoke(JsonNotationFacade, cm) match { case r: Obj => r }
    val drMeth =
      JsonNotationFacade.getClass.getDeclaredMethod("toJsonGameStatus", classOf[GameStatus])
    drMeth.setAccessible(true)
    val drJson = drMeth.invoke(JsonNotationFacade, dr) match { case r: Obj => r }
    assert(cmJson("type").str == "Checkmate")
    assert(cmJson("winner").str == "Black")
    assert(drJson("type").str == "Draw")
    assert(drJson("reason").str == "Stalemate")
  }

  test("toJsonEnPassantState serialisiert korrekt") {
    val eps = EnPassantState(
      Position.from(2, 2).toOption.getOrElse(scala.sys.error("invalid position")),
      Position.from(2, 4).toOption.getOrElse(scala.sys.error("invalid position")),
      Color.Black
    )
    val epsMeth =
      JsonNotationFacade.getClass.getDeclaredMethod("toJsonEnPassantState", classOf[EnPassantState])
    epsMeth.setAccessible(true)
    val epsJson = epsMeth.invoke(JsonNotationFacade, eps) match { case r: Obj => r }
    assert(epsJson("targetSquare").obj("file").num == 2)
    assert(epsJson("capturablePawnSquare").obj("rank").num == 4)
    assert(epsJson("pawnColor").str == "Black")
  }

  test("fromJsonGameStatus deserialisiert Checkmate und Draw korrekt") {
    val cmJson = Obj("type" -> "Checkmate", "winner" -> "White")
    val drJson = Obj("type" -> "Draw", "reason" -> "Stalemate")
    val fromJsonGameStatusMeth =
      JsonNotationFacade.getClass.getDeclaredMethod("fromJsonGameStatus", classOf[Value])
    fromJsonGameStatusMeth.setAccessible(true)
    val cmRes = (fromJsonGameStatusMeth
      .invoke(JsonNotationFacade, cmJson): @unchecked) match {
      case r: Either[String, GameStatus] => r
    }
    val drRes = (fromJsonGameStatusMeth
      .invoke(JsonNotationFacade, drJson): @unchecked) match {
      case r: Either[String, GameStatus] => r
    }
    assert(cmRes == Right(GameStatus.Checkmate(Color.White)))
    assert(drRes == Right(GameStatus.Draw(DrawReason.Stalemate)))
  }

  test("fromJsonGameStatus gibt Fehler für ungültigen Winner/Reason/Type") {
    val badCm = Obj("type" -> "Checkmate", "winner" -> "NoColor")
    val badDr = Obj("type" -> "Draw", "reason" -> "NoReason")
    val badType = Obj("type" -> "UnknownType")
    val fromJsonGameStatusMeth =
      JsonNotationFacade.getClass.getDeclaredMethod("fromJsonGameStatus", classOf[Value])
    fromJsonGameStatusMeth.setAccessible(true)
    val cmRes = (fromJsonGameStatusMeth
      .invoke(JsonNotationFacade, badCm): @unchecked) match {
      case r: Either[String, GameStatus] => r
    }
    val drRes = (fromJsonGameStatusMeth
      .invoke(JsonNotationFacade, badDr): @unchecked) match {
      case r: Either[String, GameStatus] => r
    }
    val typeRes = (fromJsonGameStatusMeth
      .invoke(JsonNotationFacade, badType): @unchecked) match {
      case r: Either[String, GameStatus] => r
    }
    assert(cmRes.isLeft && cmRes.left.get.contains("Invalid winner"))
    assert(drRes.isLeft && drRes.left.get.contains("Invalid reason"))
    assert(typeRes.isLeft && typeRes.left.get.contains("Unknown GameStatus type"))
  }

  test("parse returns error for unsupported format") {
    val res = JsonNotationFacade.parse(NotationFormat.PGN, "{}")
    assert(res.isLeft)
    assert(res.left.get.isInstanceOf[ParseFailure.StructuralError])
  }

  test("executeImport returns MappingError for invalid JSON") {
    val parsed = ParsedNotation.ParsedJsonGame("{invalid json}")
    val res = JsonNotationFacade.executeImport(parsed, ImportTarget.GameTarget)
    assert(res.isLeft)
    assert(res.left.get.isInstanceOf[ImportFailure.MappingError])
  }

  test("executeImport returns MappingError for JSON position import not implemented") {
    val parsed = ParsedNotation.ParsedJsonPosition("{}")
    val res = JsonNotationFacade.executeImport(parsed, ImportTarget.PositionTarget)
    assert(res.isLeft)
    assert(res.left.get.isInstanceOf[ImportFailure.MappingError])
  }

  test("executeImport returns IncompatibleTarget for wrong target") {
    val parsed = ParsedNotation.ParsedJsonGame("{}")
    val res = JsonNotationFacade.executeImport(parsed, ImportTarget.PositionTarget)
    assert(res.isLeft)
    assert(res.left.get.isInstanceOf[ImportFailure.IncompatibleTarget])
  }

  test("executeExport returns error for unsupported format") {
    val gs = GameState(
      board = Board.empty,
      currentPlayer = Color.White,
      moveHistory = Nil,
      status = GameStatus.Ongoing(false),
      castlingRights = CastlingRights.full,
      enPassantState = None,
      halfmoveClock = 0,
      fullmoveNumber = 1
    )
    val res = JsonNotationFacade.executeExport(gs, NotationFormat.PGN)
    assert(res.isLeft)
    assert(res.left.get.isInstanceOf[ExportFailure.UnsupportedExportFormat])
  }

  test("GameState JSON export and import roundtrip") {
    val gs = GameState(
      board = Board.empty.place(
        Position.from(4, 1).toOption.getOrElse(scala.sys.error("invalid position")),
        Piece(Color.White, PieceType.King)
      ),
      currentPlayer = Color.White,
      moveHistory = List(
        Move(
          Position.from(4, 1).toOption.getOrElse(scala.sys.error("invalid position")),
          Position.from(4, 2).toOption.getOrElse(scala.sys.error("invalid position"))
        )
      ),
      status = GameStatus.Ongoing(false),
      castlingRights = CastlingRights.full,
      enPassantState = None,
      halfmoveClock = 0,
      fullmoveNumber = 1
    )
    val exported = JsonNotationFacade.executeExport(gs, NotationFormat.JSON)
    assert(exported.isRight)
    val json = exported.toOption.getOrElse(fail("expected export success")).text
    val parsed = JsonNotationFacade.parse(NotationFormat.JSON, json)
    assert(parsed.isRight)
    val imported = JsonNotationFacade.executeImport(
      parsed.toOption.getOrElse(fail("expected parse success")),
      ImportTarget.GameTarget
    )
    assert(imported.isRight)
    val roundtrip =
      (imported.toOption.getOrElse(fail("expected import success")): @unchecked) match {
        case r: ImportResult.GameImportResult[GameState] => r.data
      }
    assert(roundtrip == gs)
  }
}
