package chess.notation.pgn

import chess.domain.model.{Color, Move, Piece, PieceType, Position}
import chess.domain.rules.GameStateRules
import chess.domain.state.GameState
import chess.notation.api.{ValidationFailure, NotationFailure}

/** Resolves a single SAN token to a legal [[Move]] in the given game state.
 *
 *  SAN (Standard Algebraic Notation) encodes only the destination square and
 *  enough disambiguation to identify a unique moving piece, so the current
 *  board position is required to find the `from` square.
 *
 *  Supported tokens:
 *  - pawn pushes:           `e4`
 *  - pawn captures:         `exd5`
 *  - piece moves:           `Nf3`, `Bb5`, `Rd1`
 *  - piece captures:        `Nxf3`, `Bxb5`
 *  - disambiguation by file: `Nbd7`, `Raxf3`
 *  - disambiguation by rank: `N5d3`
 *  - full disambiguation:    `Qa1b2`
 *  - castling:              `O-O` (king-side), `O-O-O` (queen-side)
 *  - promotions:            `e8=Q`, `exd8=Q` (and R, B, N)
 *  - check/checkmate suffixes `+`, `#` are stripped before parsing
 *
 *  Visible only within the `chess.notation.pgn` package.
 */
private[pgn] object SanResolver:

  /** Resolve `san` token to a [[Move]] in `state`, or return a failure. */
  def resolve(state: GameState, san: String): Either[NotationFailure, Move] =
    val stripped = san.stripSuffix("#").stripSuffix("+")
    if stripped == "O-O-O" || stripped == "0-0-0" then
      resolveQueenSideCastle(state)
    else if stripped == "O-O" || stripped == "0-0" then
      resolveKingSideCastle(state)
    else if stripped.contains("=") then
      resolvePromotion(state, stripped)
    else
      parsePieceTypeAndDest(stripped) match
        case None =>
          Left(ValidationFailure.InvalidValue("san", san, s"Unrecognisable SAN token: '$san'"))
        case Some((pieceType, destOpt, fileHint, rankHint, isCapture)) =>
          destOpt match
            case None =>
              Left(ValidationFailure.InvalidValue("san", san, s"Could not parse destination square in: '$san'"))
            case Some(dest) =>
              findUniqueMove(state, pieceType, dest, fileHint, rankHint, None, isCapture, san)

  // ── Castling ───────────────────────────────────────────────────────────────

  private def resolveKingSideCastle(state: GameState): Either[NotationFailure, Move] =
    val (kingFile, rookFile, rank) = (4, 6, castleRank(state.currentPlayer))
    resolveOrError(state, kingFile, rank, rookFile, rank, "O-O")

  private def resolveQueenSideCastle(state: GameState): Either[NotationFailure, Move] =
    val (kingFile, rookFile, rank) = (4, 2, castleRank(state.currentPlayer))
    resolveOrError(state, kingFile, rank, rookFile, rank, "O-O-O")

  private def castleRank(color: Color): Int = if color == Color.White then 0 else 7

  private def resolveOrError(
      state: GameState,
      fromFile: Int, fromRank: Int,
      toFile: Int,   toRank: Int,
      san: String
  ): Either[NotationFailure, Move] =
    for
      from <- Position.from(fromFile, fromRank).left.map(_ => badSan(san))
      to   <- Position.from(toFile, toRank).left.map(_ => badSan(san))
      move  = Move(from, to)
      _    <- validateLegal(state, move, san)
    yield move

  // ── Promotion ─────────────────────────────────────────────────────────────

  private def resolvePromotion(state: GameState, san: String): Either[NotationFailure, Move] =
    val parts     = san.split('=')
    if parts.length != 2 then
      return Left(ValidationFailure.InvalidValue("san", san, s"Malformed promotion token: '$san'"))
    val movePart  = parts(0)
    val promoPart = parts(1).trim
    val promoPiece = parsePromotionPiece(promoPart) match
      case None    => return Left(ValidationFailure.InvalidValue("san", san, s"Unknown promotion piece '$promoPart' in: '$san'"))
      case Some(p) => p

    parsePieceTypeAndDest(movePart) match
      case None =>
        Left(ValidationFailure.InvalidValue("san", san, s"Cannot parse move part of promotion token: '$san'"))
      case Some((_, destOpt, fileHint, rankHint, isCapture)) =>
        destOpt match
          case None =>
            Left(ValidationFailure.InvalidValue("san", san, s"No destination square in promotion token: '$san'"))
          case Some(dest) =>
            findUniqueMove(state, PieceType.Pawn, dest, fileHint, rankHint, Some(promoPiece), isCapture, san)

  private def parsePromotionPiece(s: String): Option[PieceType] = s match
    case "Q" => Some(PieceType.Queen)
    case "R" => Some(PieceType.Rook)
    case "B" => Some(PieceType.Bishop)
    case "N" => Some(PieceType.Knight)
    case _   => None

  // ── SAN structure parsing ─────────────────────────────────────────────────

  /** Returns (pieceType, destSquare, fileHint, rankHint, isCapture).
   *
   *  `fileHint` / `rankHint` disambiguate when multiple pieces of the same type
   *  can reach the destination.  Both are `None` when there is no ambiguity in
   *  the source text.
   */
  private def parsePieceTypeAndDest(
      san: String
  ): Option[(PieceType, Option[Position], Option[Int], Option[Int], Boolean)] =
    if san.isEmpty then return None

    // Piece letter: uppercase A-Z that is a valid piece symbol (not a file)
    val (pieceType, rest) = san.head match
      case 'K' => (PieceType.King,   san.tail)
      case 'Q' => (PieceType.Queen,  san.tail)
      case 'R' => (PieceType.Rook,   san.tail)
      case 'B' => (PieceType.Bishop, san.tail)
      case 'N' => (PieceType.Knight, san.tail)
      case _   => (PieceType.Pawn,   san)

    // Strip capture 'x'
    val (isCapture, afterCapture) =
      rest.lastIndexOf('x') match
        case -1  => (false, rest)
        case idx => (true,  rest.patch(idx, "", 1))   // remove the 'x' wherever it appears

    // Last two chars should be the destination square, e.g. "e4"
    if afterCapture.length < 2 then return None

    val destStr = afterCapture.takeRight(2)
    val dest    = Position.fromAlgebraic(destStr).toOption

    // Anything between piece letter and destination is disambiguation
    val disambig = afterCapture.dropRight(2)

    val (fileHint, rankHint) = disambig match
      case s if s.length == 1 && s.head >= 'a' && s.head <= 'h' =>
        (Some(s.head - 'a'), None)
      case s if s.length == 1 && s.head >= '1' && s.head <= '8' =>
        (None, Some(s.head - '1'))
      case s if s.length == 2 && s.head >= 'a' && s.head <= 'h' && s.last >= '1' && s.last <= '8' =>
        (Some(s.head - 'a'), Some(s.last - '1'))
      case _ =>
        (None, None)

    Some((pieceType, dest, fileHint, rankHint, isCapture))

  // ── Move disambiguation and legality ─────────────────────────────────────

  private def findUniqueMove(
      state:          GameState,
      pieceType:      PieceType,
      dest:           Position,
      fileHint:       Option[Int],
      rankHint:       Option[Int],
      promotionPiece: Option[PieceType],
      isCapture:      Boolean,
      san:            String
  ): Either[NotationFailure, Move] =
    val candidates = GameStateRules.legalMoves(state).filter { m =>
      m.to == dest &&
      state.board.pieceAt(m.from).exists(_.pieceType == pieceType) &&
      fileHint.forall(_ == m.from.file) &&
      rankHint.forall(_ == m.from.rank) &&
      m.promotion == promotionPiece &&
      isCapturingMove(state, m) == isCapture
    }

    candidates.size match
      case 1 => Right(candidates.head)
      case 0 => Left(ValidationFailure.InvalidValue("san", san,
                  s"No legal move for SAN token '$san' in current position"))
      case _ => Left(ValidationFailure.InvalidValue("san", san,
                  s"Ambiguous SAN token '$san': ${candidates.size} candidates"))

  /** True when `m` is a capturing move (normal capture or en passant). */
  private[pgn] def isCapturingMove(state: GameState, m: Move): Boolean =
    state.board.pieceAt(m.to).isDefined ||
    state.enPassantState.exists(ep =>
      ep.targetSquare == m.to &&
      state.board.pieceAt(m.from).exists(_.pieceType == PieceType.Pawn) &&
      m.from.file != m.to.file
    )

  private def validateLegal(state: GameState, move: Move, san: String): Either[NotationFailure, Unit] =
    if GameStateRules.legalMoves(state).contains(move) then Right(())
    else Left(ValidationFailure.InvalidValue("san", san,
           s"Move implied by '$san' is not legal in the current position"))

  private def badSan(san: String): NotationFailure =
    ValidationFailure.InvalidValue("san", san, s"Cannot construct move from SAN token '$san'")
