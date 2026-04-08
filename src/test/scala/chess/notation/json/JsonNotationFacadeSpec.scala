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
    val cmMeth = JsonNotationFacade.getClass.getDeclaredMethod("toJsonGameStatus", classOf[GameStatus])
    cmMeth.setAccessible(true)
    val cmJson = cmMeth.invoke(JsonNotationFacade, cm).asInstanceOf[Obj]
    val drMeth = JsonNotationFacade.getClass.getDeclaredMethod("toJsonGameStatus", classOf[GameStatus])
    drMeth.setAccessible(true)
    val drJson = drMeth.invoke(JsonNotationFacade, dr).asInstanceOf[Obj]
    assert(cmJson("type").str == "Checkmate")
    assert(cmJson("winner").str == "Black")
    assert(drJson("type").str == "Draw")
    assert(drJson("reason").str == "Stalemate")
  }

  test("toJsonEnPassantState serialisiert korrekt") {
    val eps = EnPassantState(Position.from(2,2).toOption.get, Position.from(2,4).toOption.get, Color.Black)
    val epsMeth = JsonNotationFacade.getClass.getDeclaredMethod("toJsonEnPassantState", classOf[EnPassantState])
    epsMeth.setAccessible(true)
    val epsJson = epsMeth.invoke(JsonNotationFacade, eps).asInstanceOf[Obj]
    assert(epsJson("targetSquare").obj("file").num == 2)
    assert(epsJson("capturablePawnSquare").obj("rank").num == 4)
    assert(epsJson("pawnColor").str == "Black")
  }

  test("fromJsonGameStatus deserialisiert Checkmate und Draw korrekt") {
    val cmJson = Obj("type" -> "Checkmate", "winner" -> "White")
    val drJson = Obj("type" -> "Draw", "reason" -> "Stalemate")
    val fromJsonGameStatusMeth = JsonNotationFacade.getClass.getDeclaredMethod("fromJsonGameStatus", classOf[Value])
    fromJsonGameStatusMeth.setAccessible(true)
    val cmRes = fromJsonGameStatusMeth.invoke(JsonNotationFacade, cmJson).asInstanceOf[Either[String, GameStatus]]
    val drRes = fromJsonGameStatusMeth.invoke(JsonNotationFacade, drJson).asInstanceOf[Either[String, GameStatus]]
    assert(cmRes == Right(GameStatus.Checkmate(Color.White)))
    assert(drRes == Right(GameStatus.Draw(DrawReason.Stalemate)))
  }

  test("fromJsonGameStatus gibt Fehler für ungültigen Winner/Reason/Type") {
    val badCm = Obj("type" -> "Checkmate", "winner" -> "NoColor")
    val badDr = Obj("type" -> "Draw", "reason" -> "NoReason")
    val badType = Obj("type" -> "UnknownType")
    val fromJsonGameStatusMeth = JsonNotationFacade.getClass.getDeclaredMethod("fromJsonGameStatus", classOf[Value])
    fromJsonGameStatusMeth.setAccessible(true)
    val cmRes = fromJsonGameStatusMeth.invoke(JsonNotationFacade, badCm).asInstanceOf[Either[String, GameStatus]]
    val drRes = fromJsonGameStatusMeth.invoke(JsonNotationFacade, badDr).asInstanceOf[Either[String, GameStatus]]
    val typeRes = fromJsonGameStatusMeth.invoke(JsonNotationFacade, badType).asInstanceOf[Either[String, GameStatus]]
    assert(cmRes.isLeft && cmRes.left.get.contains("Invalid winner"))
    assert(drRes.isLeft && drRes.left.get.contains("Invalid reason"))
    assert(typeRes.isLeft && typeRes.left.get.contains("Unknown GameStatus type"))
  }

  test("fromJsonEnPassantState wirft Exception bei fehlenden Feldern (getCause)") {
    val fromJsonEnPassantStateMeth = JsonNotationFacade.getClass.getDeclaredMethod("fromJsonEnPassantState", classOf[ujson.Value])
    fromJsonEnPassantStateMeth.setAccessible(true)
    val json = ujson.Obj() // keine Felder
    val thrown = intercept[java.lang.reflect.InvocationTargetException] {
      fromJsonEnPassantStateMeth.invoke(JsonNotationFacade, json)
    }
    assert(thrown.getCause.isInstanceOf[NoSuchElementException])
  }

  test("fromJsonEnPassantState gibt Fehler bei ungültigem pawnColor") {
    val fromJsonEnPassantStateMeth = JsonNotationFacade.getClass.getDeclaredMethod("fromJsonEnPassantState", classOf[ujson.Value])
    fromJsonEnPassantStateMeth.setAccessible(true)
    val json = ujson.Obj(
      "targetSquare" -> ujson.Obj("file" -> 1, "rank" -> 2),
      "capturablePawnSquare" -> ujson.Obj("file" -> 1, "rank" -> 3),
      "pawnColor" -> "NoColor"
    )
    val res = fromJsonEnPassantStateMeth.invoke(JsonNotationFacade, json).asInstanceOf[Either[String, _]]
    assert(res.isLeft)
    assert(res.left.get.contains("Invalid pawnColor"))
  }

  test("PieceType.values.find: promotion None bei ungültigem promotion") {
    val fromJsonMoveMeth = JsonNotationFacade.getClass.getDeclaredMethod("fromJsonMove", classOf[ujson.Value])
    fromJsonMoveMeth.setAccessible(true)
    val json = ujson.Obj(
      "from" -> ujson.Obj("file" -> 1, "rank" -> 2),
      "to" -> ujson.Obj("file" -> 1, "rank" -> 3),
      "promotion" -> "NoPiece"
    )
    val res = fromJsonMoveMeth.invoke(JsonNotationFacade, json)
    // Sollte ein Move mit promotion=None sein
    assert(res.isInstanceOf[Right[_, _]])
    val move = res.asInstanceOf[Right[_, chess.domain.model.Move]].value
    assert(move.promotion.isEmpty)
  }

  test("Exception-Branch in fromJsonPosition wird abgedeckt") {
    val fromJsonPositionMeth = JsonNotationFacade.getClass.getDeclaredMethod("fromJsonPosition", classOf[ujson.Value])
    fromJsonPositionMeth.setAccessible(true)
    val json = ujson.Obj("file" -> "notAnInt", "rank" -> 2)
    val res = fromJsonPositionMeth.invoke(JsonNotationFacade, json).asInstanceOf[Either[String, _]]
    assert(res.isLeft)
    assert(res.left.get.contains("notAnInt"))
  }

  test("Fehleraggregation bei mehreren Fehlern (mindestens zwei)") {
    val fromJsonBoardMeth = JsonNotationFacade.getClass.getDeclaredMethod("fromJsonBoard", classOf[ujson.Value])
    fromJsonBoardMeth.setAccessible(true)
    val arr = ujson.Arr(
      ujson.Obj("pos" -> ujson.Obj("file" -> 1, "rank" -> 2), "piece" -> ujson.Obj("color" -> "NoColor", "pieceType" -> "NoPiece")),
      ujson.Obj("pos" -> ujson.Obj("file" -> 1, "rank" -> 3), "piece" -> ujson.Obj("color" -> "NoColor", "pieceType" -> "NoPiece"))
    )
    val res = fromJsonBoardMeth.invoke(JsonNotationFacade, arr).asInstanceOf[Either[String, _]]
    assert(res.isLeft)
    assert(res.left.get.contains(", ")) // mehrere Fehler zusammengeführt
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
      board = Board.empty.place(Position.from(4, 1).toOption.get, Piece(Color.White, PieceType.King)),
      currentPlayer = Color.White,
      moveHistory = List(Move(Position.from(4, 1).toOption.get, Position.from(4, 2).toOption.get)),
      status = GameStatus.Ongoing(false),
      castlingRights = CastlingRights.full,
      enPassantState = None,
      halfmoveClock = 0,
      fullmoveNumber = 1
    )
    val exported = JsonNotationFacade.executeExport(gs, NotationFormat.JSON)
    assert(exported.isRight)
    val json = exported.toOption.get.text
    val parsed = JsonNotationFacade.parse(NotationFormat.JSON, json)
    assert(parsed.isRight)
    val imported = JsonNotationFacade.executeImport(parsed.toOption.get, ImportTarget.GameTarget)
    assert(imported.isRight)
    val roundtrip = imported.toOption.get.asInstanceOf[ImportResult.GameImportResult[GameState]].data
    assert(roundtrip == gs)
  }
}
