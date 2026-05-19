package chess.aiservice.engine

import chess.adapter.ai.remote.RemoteAiMoveDto
import chess.domain.model.*
import chess.domain.rules.GameStateRules
import chess.domain.rules.application.MoveApplier
import chess.domain.state.GameState

object MinimaxEngine:

  /** Selects the best move using Minimax with Alpha-Beta pruning up to `depth`. */
  def selectBestMove(
      state: GameState,
      legalMovesDto: List[RemoteAiMoveDto],
      depth: Int
  ): Option[RemoteAiMoveDto] =
    val colorToMove = state.currentPlayer
    val isMaximizing = colorToMove == Color.White

    val initialBest = if isMaximizing then Double.NegativeInfinity else Double.PositiveInfinity

    val domainLegalMoves = GameStateRules.legalMoves(state).toSet

    val (_, bestMoveOption) = legalMovesDto.foldLeft((initialBest, Option.empty[RemoteAiMoveDto])) {
      case ((currentBestValue, currentBestMove), dto) =>
        val fromPos = parsePos(dto.from)
        val toPos = parsePos(dto.to)
        val promotionType = dto.promotion.flatMap(parsePieceType)
        val move = Move(fromPos, toPos, promotionType)

        if domainLegalMoves.contains(move) then
          MoveApplier.applyMove(state.board, move, state.castlingRights, state.enPassantState) match
            case Right(MoveResult.Applied(nextBoard)) =>
              val nextState = state.copy(board = nextBoard, currentPlayer = colorToMove.opposite)
              val value = minimax(nextState, depth - 1, Double.NegativeInfinity, Double.PositiveInfinity, !isMaximizing)
              
              if isMaximizing && value > currentBestValue then
                (value, Some(dto))
              else if !isMaximizing && value < currentBestValue then
                (value, Some(dto))
              else
                (currentBestValue, currentBestMove)
            case _ => (currentBestValue, currentBestMove)
        else
          (currentBestValue, currentBestMove)
    }

    bestMoveOption.orElse(legalMovesDto.headOption)

  private def minimax(state: GameState, depth: Int, alpha: Double, beta: Double, isMaximizing: Boolean): Double =
    if depth == 0 then
      evaluateBoard(state.board)
    else
      val moves = GameStateRules.legalMoves(state).toList
      if moves.isEmpty then
        // Could be checkmate or stalemate
        val inCheck = chess.domain.rules.validation.CheckValidator.isKingInCheck(state.board, state.currentPlayer)
        if inCheck then
          if isMaximizing then -99999.0 else 99999.0
        else
          0.0 // Stalemate
      else
        evaluateMoves(state, moves, depth, alpha, beta, isMaximizing, if isMaximizing then Double.NegativeInfinity else Double.PositiveInfinity)

  @scala.annotation.tailrec
  private def evaluateMoves(state: GameState, moves: List[Move], depth: Int, alpha: Double, beta: Double, isMaximizing: Boolean, currentBest: Double): Double =
    moves match
      case Nil => currentBest
      case move :: rest =>
        MoveApplier.applyMove(state.board, move, state.castlingRights, state.enPassantState) match
          case Right(MoveResult.Applied(nextBoard)) =>
            val nextState = state.copy(board = nextBoard, currentPlayer = state.currentPlayer.opposite)
            val eval = minimax(nextState, depth - 1, alpha, beta, !isMaximizing)
            if isMaximizing then
              val maxEval = math.max(currentBest, eval)
              val a = math.max(alpha, eval)
              if beta <= a then maxEval else evaluateMoves(state, rest, depth, a, beta, isMaximizing, maxEval)
            else
              val minEval = math.min(currentBest, eval)
              val b = math.min(beta, eval)
              if b <= alpha then minEval else evaluateMoves(state, rest, depth, alpha, b, isMaximizing, minEval)

          case Right(MoveResult.PromotionRequired(nextBoard, sq, color)) =>
            chess.domain.rules.application.PromotionApplier.applyPromotion(nextBoard, sq, color, PieceType.Queen) match
              case Right(promotedBoard) =>
                val nextState = state.copy(board = promotedBoard, currentPlayer = state.currentPlayer.opposite)
                val eval = minimax(nextState, depth - 1, alpha, beta, !isMaximizing)
                if isMaximizing then
                  val maxEval = math.max(currentBest, eval)
                  val a = math.max(alpha, eval)
                  if beta <= a then maxEval else evaluateMoves(state, rest, depth, a, beta, isMaximizing, maxEval)
                else
                  val minEval = math.min(currentBest, eval)
                  val b = math.min(beta, eval)
                  if b <= alpha then minEval else evaluateMoves(state, rest, depth, alpha, b, isMaximizing, minEval)
              case Left(_) =>
                evaluateMoves(state, rest, depth, alpha, beta, isMaximizing, currentBest)

          case Left(_) =>
            evaluateMoves(state, rest, depth, alpha, beta, isMaximizing, currentBest)

  private def evaluateBoard(board: Board): Double =
    board.piecesIterator.map { case (_, piece) =>
      val value = pieceValue(piece.pieceType)
      if piece.color == Color.White then value else -value
    }.sum

  private def pieceValue(pieceType: PieceType): Double =
    pieceType match
      case PieceType.Pawn   => 10.0
      case PieceType.Knight => 30.0
      case PieceType.Bishop => 30.0
      case PieceType.Rook   => 50.0
      case PieceType.Queen  => 90.0
      case PieceType.King   => 900.0

  private def parsePos(s: String): Position =
    // e.g., "e2"
    val file = s.charAt(0) - 'a'
    val rank = s.charAt(1) - '1'
    Position.from(file, rank) match
      case Right(p) => p
      case Left(_) => throw new RuntimeException(s"Invalid position string: $s")

  private def parsePieceType(s: String): Option[PieceType] =
    s.toLowerCase match
      case "q" => Some(PieceType.Queen)
      case "r" => Some(PieceType.Rook)
      case "n" => Some(PieceType.Knight)
      case "b" => Some(PieceType.Bishop)
      case _   => None
