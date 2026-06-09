package chess.pgn

import chess.domain.model.{Color, DrawReason, GameStatus}
import chess.domain.state.{GameState, GameStateFactory}
import chess.notation.api._
import chess.notation.pgn.PgnNotationFacade
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests for [[PgnNotationFacade.exportWithHeaders]].
  *
  * Verifies the PGN header assembly seam used by a future History / archive materialiser: tag block
  * formatting, separator blank line, result tokens, and edge cases (empty headers, cancelled
  * games).
  */
class PgnWithHeadersSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val initial = GameStateFactory.initial()

  // ── Header formatting ────────────────────────────────────────────────────────

  "PgnNotationFacade.exportWithHeaders" should "format each header as [Tag \"Value\"]" in {
    val result = PgnNotationFacade
      .exportWithHeaders(
        initial,
        Seq("Event" -> "Test Game")
      )
      .value
    result.text should startWith("""[Event "Test Game"]""")
  }

  it should "separate the header block from the movetext with a blank line" in {
    val result = PgnNotationFacade
      .exportWithHeaders(
        initial,
        Seq("Event" -> "?")
      )
      .value
    result.text should include("\n\n")
    val parts = result.text.split("\n\n", 2)
    parts should have size 2
    parts(0) shouldBe """[Event "?"]"""
    parts(1) shouldBe "*"
  }

  it should "emit multiple headers in the order supplied" in {
    val headers = Seq(
      "Event" -> "Live Game",
      "Site" -> "Online",
      "Date" -> "2026.04.20",
      "Round" -> "1",
      "White" -> "HumanLocal",
      "Black" -> "AI",
      "Result" -> "*"
    )
    val text = PgnNotationFacade.exportWithHeaders(initial, headers).value.text
    val lines = text.linesIterator.toList

    lines(0) shouldBe """[Event "Live Game"]"""
    lines(1) shouldBe """[Site "Online"]"""
    lines(2) shouldBe """[Date "2026.04.20"]"""
    lines(3) shouldBe """[Round "1"]"""
    lines(4) shouldBe """[White "HumanLocal"]"""
    lines(5) shouldBe """[Black "AI"]"""
    lines(6) shouldBe """[Result "*"]"""
  }

  it should "produce a headerless document when given an empty header sequence" in {
    val result = PgnNotationFacade.exportWithHeaders(initial, Seq.empty).value
    val noHeadersRef = PgnNotationFacade.executeExport(initial, NotationFormat.PGN).value
    result.text shouldBe noHeadersRef.text
  }

  // ── Result token for completed games ─────────────────────────────────────────

  it should "include 1-0 result token for Checkmate(White) state" in {
    val state = initial.copy(status = GameStatus.Checkmate(Color.White))
    val text = PgnNotationFacade.exportWithHeaders(state, Seq("Result" -> "1-0")).value.text
    text should endWith("1-0")
  }

  it should "include 0-1 result token for Checkmate(Black) state" in {
    val state = initial.copy(status = GameStatus.Checkmate(Color.Black))
    val text = PgnNotationFacade.exportWithHeaders(state, Seq("Result" -> "0-1")).value.text
    text should endWith("0-1")
  }

  it should "include 1/2-1/2 result token for Draw state" in {
    val state = initial.copy(status = GameStatus.Draw(DrawReason.Stalemate))
    val text = PgnNotationFacade.exportWithHeaders(state, Seq("Result" -> "1/2-1/2")).value.text
    text should endWith("1/2-1/2")
  }

  it should "include 1-0 result token for Resigned(White) — White resigned, Black wins" in {
    val state = initial.copy(status = GameStatus.Resigned(Color.White))
    val text = PgnNotationFacade.exportWithHeaders(state, Seq("Result" -> "1-0")).value.text
    text should endWith("1-0")
  }

  // ── Cancelled / incomplete game ──────────────────────────────────────────────

  it should "use * result token for Ongoing state (cancelled session)" in {
    // A cancelled game has GameStatus.Ongoing — the session was closed
    // administratively without a game result.  PGN standard: * = unfinished.
    val text = PgnNotationFacade
      .exportWithHeaders(
        initial,
        Seq("Event" -> "Cancelled", "Result" -> "*")
      )
      .value
      .text
    text should endWith("*")
  }

  it should "emit a partial move list for a cancelled game with moves" in {
    // Import one move so the state has a non-empty move history, then
    // leave status Ongoing to simulate a cancelled mid-game session.
    val oneMove = PgnNotationFacade
      .parseAndImport(NotationFormat.PGN, "1. e4 *", ImportTarget.GameTarget)
      .value
      .asInstanceOf[ImportResult.GameImportResult[GameState]]
      .data
    val text = PgnNotationFacade
      .exportWithHeaders(
        oneMove,
        Seq("Event" -> "Cancelled", "Result" -> "*")
      )
      .value
      .text
    text should include("1. e4")
    text should endWith("*")
  }

  // ── ExportResult format field ────────────────────────────────────────────────

  it should "carry NotationFormat.PGN in the ExportResult" in {
    val result = PgnNotationFacade
      .exportWithHeaders(
        initial,
        Seq("Event" -> "?")
      )
      .value
    result.format shouldBe NotationFormat.PGN
  }
