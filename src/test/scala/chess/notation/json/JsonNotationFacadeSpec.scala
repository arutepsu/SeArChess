package chess.notation.json

import org.scalatest.funsuite.AnyFunSuite
import chess.domain.state._
import chess.domain.model._
import chess.notation.api._

class JsonNotationFacadeSpec extends AnyFunSuite {
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
