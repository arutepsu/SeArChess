package chess.lichessbridge

import chess.adapter.ai.remote.RemoteAiMoveDto
import chess.domain.model.{Move, PieceType, Position}
import chess.domain.rules.GameStateRules
import chess.domain.state.GameState
import chess.notation.api.{ImportResult, ImportTarget, NotationFormat}
import chess.notation.fen.FenNotationFacade

/** Adapts Lichess game-stream data (initialFen + UCI moves string) into domain types needed
  * by the AI service: current GameState, FEN string, legal move DTOs.
  *
  * "startpos" is treated as the standard chess starting position.
  * Move replay delegates to GameStateRules.applyMove so all chess rules are applied correctly.
  */
object GamePositionAdapter:

  val StandardStartFen: String =
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  /** Rebuild the current GameState by parsing initialFen and replaying every UCI move.
    * Returns Left with a description if the FEN is invalid or any move is illegal.
    */
  def toGameState(initialFen: String, movesStr: String): Either[String, GameState] =
    val fen = if initialFen == "startpos" || initialFen.isBlank then StandardStartFen else initialFen
    parseFen(fen).flatMap { initial =>
      val uciList = movesStr.split(' ').toList.filter(_.nonEmpty)
      replayMoves(initial, uciList)
    }

  /** Convert all legal moves for the current player into RemoteAiMoveDto objects. */
  def toLegalMoveDtos(state: GameState): List[RemoteAiMoveDto] =
    GameStateRules.legalMoves(state)
      .toList
      .sortBy(m => m.from.toString + m.to.toString + m.promotion.fold("")(promotionChar))
      .map(moveToDto)

  /** Serialize the current board position as a FEN string. Falls back to StandardStartFen on error. */
  def toCurrentFen(state: GameState): String =
    FenNotationFacade.executeExport(state, NotationFormat.FEN)
      .fold(_ => StandardStartFen, _.text)

  /** "White" or "Black" — the value expected by the AI service sideToMove field. */
  def toSideToMove(state: GameState): String =
    import chess.domain.model.Color
    state.currentPlayer match
      case Color.White => "White"
      case Color.Black => "Black"

  // ── Private helpers ──────────────────────────────────────────────────────────

  private def parseFen(fen: String): Either[String, GameState] =
    FenNotationFacade.parseAndImport(NotationFormat.FEN, fen, ImportTarget.PositionTarget) match
      case Right(r: ImportResult.PositionImportResult[GameState]) => Right(r.data)
      case Right(r: ImportResult.GameImportResult[GameState])     => Right(r.data)
      case Right(_)                                                => Left("Unexpected FEN import result type")
      case Left(err)                                               => Left(err.toString)

  private def replayMoves(state: GameState, uciMoves: List[String]): Either[String, GameState] =
    uciMoves.foldLeft[Either[String, GameState]](Right(state)) { (acc, uci) =>
      acc.flatMap { s =>
        parseUciMove(uci).flatMap { m =>
          GameStateRules.applyMove(s, m).left.map(_.toString)
        }
      }
    }

  private def parseUciMove(uci: String): Either[String, Move] =
    if uci.length < 4 || uci.length > 5 then Left(s"Invalid UCI move length: '$uci'")
    else
      for
        from  <- Position.fromAlgebraic(uci.substring(0, 2)).left.map(_.toString)
        to    <- Position.fromAlgebraic(uci.substring(2, 4)).left.map(_.toString)
        promo  = if uci.length == 5 then uciPromoChar(uci.charAt(4)) else None
      yield Move(from, to, promo)

  private def uciPromoChar(c: Char): Option[PieceType] = c.toLower match
    case 'q' => Some(PieceType.Queen)
    case 'r' => Some(PieceType.Rook)
    case 'b' => Some(PieceType.Bishop)
    case 'n' => Some(PieceType.Knight)
    case _   => None

  private def moveToDto(move: Move): RemoteAiMoveDto =
    RemoteAiMoveDto(
      from      = move.from.toString,
      to        = move.to.toString,
      promotion = move.promotion.map(promotionChar)
    )

  private def promotionChar(pt: PieceType): String = pt match
    case PieceType.Queen  => "q"
    case PieceType.Rook   => "r"
    case PieceType.Bishop => "b"
    case PieceType.Knight => "n"
    case _                => "q"
