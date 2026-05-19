package chess.aiservice.engine

import chess.adapter.ai.remote.RemoteAiMoveDto
import chess.domain.model.*
import chess.domain.rules.GameStateRules
import chess.domain.rules.application.MoveApplier
import chess.domain.state.GameState

object MinimaxEngine:

<<<<<<< HEAD
  // Piece-Square Tables (PSTs) from White's perspective
  // Index = rank * 8 + file (rank 0 = White's back rank, rank 7 = White's front rank/Black's back rank)
  private val pawnPST = Array(
    0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 1.0, 1.0, -2.0, -2.0, 1.0, 1.0, 0.5, 0.5, -0.5,
    -1.0, 0.0, 0.0, -1.0, -0.5, 0.5, 0.0, 0.0, 0.0, 2.0, 2.0, 0.0, 0.0, 0.0, 0.5, 0.5, 1.0, 2.5,
    2.5, 1.0, 0.5, 0.5, 1.0, 1.0, 2.0, 3.0, 3.0, 2.0, 1.0, 1.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0,
    5.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
  )

  private val knightPST = Array(
    -5.0, -4.0, -3.0, -3.0, -3.0, -3.0, -4.0, -5.0, -4.0, -2.0, 0.0, 0.5, 0.5, 0.0, -2.0, -4.0,
    -3.0, 0.5, 1.0, 1.5, 1.5, 1.0, 0.5, -3.0, -3.0, 0.0, 1.5, 2.0, 2.0, 1.5, 0.0, -3.0, -3.0, 0.5,
    1.5, 2.0, 2.0, 1.5, 0.5, -3.0, -3.0, 0.0, 1.0, 1.5, 1.5, 1.0, 0.0, -3.0, -4.0, -2.0, 0.0, 0.0,
    0.0, 0.0, -2.0, -4.0, -5.0, -4.0, -3.0, -3.0, -3.0, -3.0, -4.0, -5.0
  )

  private val bishopPST = Array(
    -2.0, -1.0, -1.0, -1.0, -1.0, -1.0, -1.0, -2.0, -1.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.5, -1.0, -1.0,
    1.0, 1.0, 1.0, 1.0, 1.0, 1.0, -1.0, -1.0, 0.0, 1.0, 1.5, 1.5, 1.0, 0.0, -1.0, -1.0, 0.5, 0.5,
    1.5, 1.5, 0.5, 0.5, -1.0, -1.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0, -1.0, -1.0, 0.0, 0.0, 0.0, 0.0,
    0.0, 0.0, -1.0, -2.0, -1.0, -1.0, -1.0, -1.0, -1.0, -1.0, -2.0
  )

  private val rookPST = Array(
    0.0, 0.0, 0.0, 0.5, 0.5, 0.0, 0.0, 0.0, -0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -0.5, -0.5, 0.0,
    0.0, 0.0, 0.0, 0.0, 0.0, -0.5, -0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -0.5, -0.5, 0.0, 0.0, 0.0,
    0.0, 0.0, 0.0, -0.5, -0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -0.5, 0.5, 1.0, 1.0, 1.0, 1.0, 1.0,
    1.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
  )

  private val queenPST = Array(
    -2.0, -1.0, -1.0, -0.5, -0.5, -1.0, -1.0, -2.0, -1.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, -1.0, -1.0,
    0.5, 0.5, 0.5, 0.5, 0.0, 0.0, -1.0, 0.0, 0.0, 0.5, 0.5, 0.5, 0.5, 0.0, -0.5, -0.5, 0.0, 0.5,
    0.5, 0.5, 0.5, 0.0, -0.5, -1.0, 0.0, 0.5, 0.5, 0.5, 0.5, 0.0, -1.0, -1.0, 0.0, 0.0, 0.0, 0.0,
    0.0, 0.0, -1.0, -2.0, -1.0, -1.0, -0.5, -0.5, -1.0, -1.0, -2.0
  )

  private val kingMiddleGamePST = Array(
    2.0, 3.0, 1.0, 0.0, 0.0, 1.0, 3.0, 2.0, 2.0, 2.0, 0.0, 0.0, 0.0, 0.0, 2.0, 2.0, -1.0, -2.0,
    -2.0, -2.0, -2.0, -2.0, -2.0, -1.0, -2.0, -3.0, -3.0, -4.0, -4.0, -3.0, -3.0, -2.0, -3.0, -4.0,
    -4.0, -5.0, -5.0, -4.0, -4.0, -3.0, -3.0, -4.0, -4.0, -5.0, -5.0, -4.0, -4.0, -3.0, -3.0, -4.0,
    -4.0, -5.0, -5.0, -4.0, -4.0, -3.0, -3.0, -4.0, -4.0, -5.0, -5.0, -4.0, -4.0, -3.0
  )

  private val kingEndgamePST = Array(
    -5.0, -3.0, -3.0, -3.0, -3.0, -3.0, -3.0, -5.0, -3.0, -1.0, 0.0, 0.0, 0.0, 0.0, -1.0, -3.0,
    -3.0, 0.0, 2.0, 3.0, 3.0, 2.0, 0.0, -3.0, -3.0, 0.0, 3.0, 4.0, 4.0, 3.0, 0.0, -3.0, -3.0, 0.0,
    3.0, 4.0, 4.0, 3.0, 0.0, -3.0, -3.0, 0.0, 2.0, 3.0, 3.0, 2.0, 0.0, -3.0, -3.0, -1.0, 0.0, 0.0,
    0.0, 0.0, -1.0, -3.0, -5.0, -3.0, -3.0, -3.0, -3.0, -3.0, -3.0, -5.0
  )

=======
>>>>>>> 97d0df0b (added ai for lichess)
  /** Selects the best move using Minimax with Alpha-Beta pruning up to `depth`. */
  def selectBestMove(
      state: GameState,
      legalMovesDto: List[RemoteAiMoveDto],
      depth: Int
  ): Option[RemoteAiMoveDto] =
    val colorToMove = state.currentPlayer
    val isMaximizing = colorToMove == Color.White

<<<<<<< HEAD
    val domainLegalMoves = GameStateRules.legalMoves(state).toSet

    // Parse and filter valid moves, sorting them for root-level pruning
    val sortedMoves = legalMovesDto
      .flatMap { dto =>
=======
    val initialBest = if isMaximizing then Double.NegativeInfinity else Double.PositiveInfinity

    val domainLegalMoves = GameStateRules.legalMoves(state).toSet

    val (_, bestMoveOption) = legalMovesDto.foldLeft((initialBest, Option.empty[RemoteAiMoveDto])) {
      case ((currentBestValue, currentBestMove), dto) =>
>>>>>>> 97d0df0b (added ai for lichess)
        val fromPos = parsePos(dto.from)
        val toPos = parsePos(dto.to)
        val promotionType = dto.promotion.flatMap(parsePieceType)
        val move = Move(fromPos, toPos, promotionType)

<<<<<<< HEAD
        if domainLegalMoves.contains(move) then Some((dto, move, scoreMove(state.board, move)))
        else None
      }
      .sortBy { case (_, _, score) => -score }

    if sortedMoves.isEmpty then legalMovesDto.headOption
    else if isMaximizing then
      searchMaximizingRoot(state, sortedMoves, Double.NegativeInfinity, None, depth)
        .orElse(sortedMoves.headOption.map(_._1))
    else
      searchMinimizingRoot(state, sortedMoves, Double.PositiveInfinity, None, depth)
        .orElse(sortedMoves.headOption.map(_._1))

  @scala.annotation.tailrec
  private def searchMaximizingRoot(
      state: GameState,
      moves: List[(RemoteAiMoveDto, Move, Double)],
      alpha: Double,
      bestMove: Option[RemoteAiMoveDto],
      depth: Int
  ): Option[RemoteAiMoveDto] =
    moves match
      case Nil => bestMove
      case (dto, move, _) :: rest =>
        MoveApplier.applyMove(state.board, move, state.castlingRights, state.enPassantState) match
          case Right(MoveResult.Applied(nextBoard)) =>
            val nextState =
              state.copy(board = nextBoard, currentPlayer = state.currentPlayer.opposite)
            val value = minimax(nextState, depth - 1, alpha, Double.PositiveInfinity, false)
            if value > alpha then searchMaximizingRoot(state, rest, value, Some(dto), depth)
            else searchMaximizingRoot(state, rest, alpha, bestMove, depth)
          case Right(MoveResult.PromotionRequired(nextBoard, sq, color)) =>
            val promoType = move.promotion.getOrElse(PieceType.Queen)
            chess.domain.rules.application.PromotionApplier
              .applyPromotion(nextBoard, sq, color, promoType) match
              case Right(promotedBoard) =>
                val nextState =
                  state.copy(board = promotedBoard, currentPlayer = state.currentPlayer.opposite)
                val value = minimax(nextState, depth - 1, alpha, Double.PositiveInfinity, false)
                if value > alpha then searchMaximizingRoot(state, rest, value, Some(dto), depth)
                else searchMaximizingRoot(state, rest, alpha, bestMove, depth)
              case _ =>
                searchMaximizingRoot(state, rest, alpha, bestMove, depth)
          case _ =>
            searchMaximizingRoot(state, rest, alpha, bestMove, depth)

  @scala.annotation.tailrec
  private def searchMinimizingRoot(
      state: GameState,
      moves: List[(RemoteAiMoveDto, Move, Double)],
      beta: Double,
      bestMove: Option[RemoteAiMoveDto],
      depth: Int
  ): Option[RemoteAiMoveDto] =
    moves match
      case Nil => bestMove
      case (dto, move, _) :: rest =>
        MoveApplier.applyMove(state.board, move, state.castlingRights, state.enPassantState) match
          case Right(MoveResult.Applied(nextBoard)) =>
            val nextState =
              state.copy(board = nextBoard, currentPlayer = state.currentPlayer.opposite)
            val value = minimax(nextState, depth - 1, Double.NegativeInfinity, beta, true)
            if value < beta then searchMinimizingRoot(state, rest, value, Some(dto), depth)
            else searchMinimizingRoot(state, rest, beta, bestMove, depth)
          case Right(MoveResult.PromotionRequired(nextBoard, sq, color)) =>
            val promoType = move.promotion.getOrElse(PieceType.Queen)
            chess.domain.rules.application.PromotionApplier
              .applyPromotion(nextBoard, sq, color, promoType) match
              case Right(promotedBoard) =>
                val nextState =
                  state.copy(board = promotedBoard, currentPlayer = state.currentPlayer.opposite)
                val value = minimax(nextState, depth - 1, Double.NegativeInfinity, beta, true)
                if value < beta then searchMinimizingRoot(state, rest, value, Some(dto), depth)
                else searchMinimizingRoot(state, rest, beta, bestMove, depth)
              case _ =>
                searchMinimizingRoot(state, rest, beta, bestMove, depth)
          case _ =>
            searchMinimizingRoot(state, rest, beta, bestMove, depth)

  private def minimax(
      state: GameState,
      depth: Int,
      alpha: Double,
      beta: Double,
      isMaximizing: Boolean
  ): Double =
    if depth == 0 then evaluateBoard(state.board)
    else
      val moves = GameStateRules.legalMoves(state).toList
      if moves.isEmpty then
        val inCheck = chess.domain.rules.validation.CheckValidator
          .isKingInCheck(state.board, state.currentPlayer)
        if inCheck then if isMaximizing then -99999.0 else 99999.0
        else 0.0 // Stalemate
      else
        val sortedMoves = moves.sortBy(m => -scoreMove(state.board, m))
        evaluateMoves(
          state,
          sortedMoves,
          depth,
          alpha,
          beta,
          isMaximizing,
          if isMaximizing then Double.NegativeInfinity else Double.PositiveInfinity
        )

  @scala.annotation.tailrec
  private def evaluateMoves(
      state: GameState,
      moves: List[Move],
      depth: Int,
      alpha: Double,
      beta: Double,
      isMaximizing: Boolean,
      currentBest: Double
  ): Double =
=======
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
>>>>>>> 97d0df0b (added ai for lichess)
    moves match
      case Nil => currentBest
      case move :: rest =>
        MoveApplier.applyMove(state.board, move, state.castlingRights, state.enPassantState) match
          case Right(MoveResult.Applied(nextBoard)) =>
<<<<<<< HEAD
            val nextState =
              state.copy(board = nextBoard, currentPlayer = state.currentPlayer.opposite)
=======
            val nextState = state.copy(board = nextBoard, currentPlayer = state.currentPlayer.opposite)
>>>>>>> 97d0df0b (added ai for lichess)
            val eval = minimax(nextState, depth - 1, alpha, beta, !isMaximizing)
            if isMaximizing then
              val maxEval = math.max(currentBest, eval)
              val a = math.max(alpha, eval)
<<<<<<< HEAD
              if beta <= a then maxEval
              else evaluateMoves(state, rest, depth, a, beta, isMaximizing, maxEval)
            else
              val minEval = math.min(currentBest, eval)
              val b = math.min(beta, eval)
              if b <= alpha then minEval
              else evaluateMoves(state, rest, depth, alpha, b, isMaximizing, minEval)

          case Right(MoveResult.PromotionRequired(nextBoard, sq, color)) =>
            chess.domain.rules.application.PromotionApplier
              .applyPromotion(nextBoard, sq, color, PieceType.Queen) match
              case Right(promotedBoard) =>
                val nextState =
                  state.copy(board = promotedBoard, currentPlayer = state.currentPlayer.opposite)
=======
              if beta <= a then maxEval else evaluateMoves(state, rest, depth, a, beta, isMaximizing, maxEval)
            else
              val minEval = math.min(currentBest, eval)
              val b = math.min(beta, eval)
              if b <= alpha then minEval else evaluateMoves(state, rest, depth, alpha, b, isMaximizing, minEval)

          case Right(MoveResult.PromotionRequired(nextBoard, sq, color)) =>
            chess.domain.rules.application.PromotionApplier.applyPromotion(nextBoard, sq, color, PieceType.Queen) match
              case Right(promotedBoard) =>
                val nextState = state.copy(board = promotedBoard, currentPlayer = state.currentPlayer.opposite)
>>>>>>> 97d0df0b (added ai for lichess)
                val eval = minimax(nextState, depth - 1, alpha, beta, !isMaximizing)
                if isMaximizing then
                  val maxEval = math.max(currentBest, eval)
                  val a = math.max(alpha, eval)
<<<<<<< HEAD
                  if beta <= a then maxEval
                  else evaluateMoves(state, rest, depth, a, beta, isMaximizing, maxEval)
                else
                  val minEval = math.min(currentBest, eval)
                  val b = math.min(beta, eval)
                  if b <= alpha then minEval
                  else evaluateMoves(state, rest, depth, alpha, b, isMaximizing, minEval)
=======
                  if beta <= a then maxEval else evaluateMoves(state, rest, depth, a, beta, isMaximizing, maxEval)
                else
                  val minEval = math.min(currentBest, eval)
                  val b = math.min(beta, eval)
                  if b <= alpha then minEval else evaluateMoves(state, rest, depth, alpha, b, isMaximizing, minEval)
>>>>>>> 97d0df0b (added ai for lichess)
              case Left(_) =>
                evaluateMoves(state, rest, depth, alpha, beta, isMaximizing, currentBest)

          case Left(_) =>
            evaluateMoves(state, rest, depth, alpha, beta, isMaximizing, currentBest)

<<<<<<< HEAD
  private def scoreMove(board: Board, move: Move): Double =
    val attacker = board.pieceAt(move.from).map(_.pieceType).getOrElse(PieceType.Pawn)
    val victimOpt = board.pieceAt(move.to).map(_.pieceType)

    val isEnPassant =
      attacker == PieceType.Pawn && move.from.file != move.to.file && victimOpt.isEmpty

    val captureScore = victimOpt match
      case Some(victim) =>
        100.0 + pieceValue(victim) * 10.0 - pieceValue(attacker)
      case None =>
        if isEnPassant then 100.0 + pieceValue(PieceType.Pawn) * 10.0 - pieceValue(attacker)
        else 0.0

    val promoScore = move.promotion.map(p => pieceValue(p) * 2.0).getOrElse(0.0)
    captureScore + promoScore

  private def isEndgame(board: Board): Boolean =
    val nonPawnMaterial = board.piecesIterator.foldLeft((0.0, 0.0)) {
      case ((whiteMat, blackMat), (_, piece)) =>
        if piece.pieceType != PieceType.Pawn && piece.pieceType != PieceType.King then
          val v = pieceValue(piece.pieceType)
          if piece.color == Color.White then (whiteMat + v, blackMat)
          else (whiteMat, blackMat + v)
        else (whiteMat, blackMat)
    }
    nonPawnMaterial._1 <= 130.0 && nonPawnMaterial._2 <= 130.0

  private def kingShieldPenalty(board: Board, kingPos: Position, color: Color): Double =
    val rank = if color == Color.White then 1 else 6
    val filesToCheck =
      if kingPos.file >= 5 then Seq(5, 6, 7) // Kingside
      else if kingPos.file <= 2 then Seq(0, 1, 2) // Queenside
      else Seq.empty

    if filesToCheck.nonEmpty then
      val missingCount = filesToCheck.count { f =>
        val pOpt = Position.from(f, rank).toOption.flatMap(board.pieceAt)
        pOpt match
          case Some(Piece(c, PieceType.Pawn)) if c == color => false
          case _                                            => true
      }
      missingCount * 0.5
    else 0.0

  private def evaluateBoard(board: Board): Double =
    val piecesList = board.piecesIterator.toList

    val whitePawns = piecesList
      .filter { case (_, piece) => piece.color == Color.White && piece.pieceType == PieceType.Pawn }
      .map(_._1)
    val blackPawns = piecesList
      .filter { case (_, piece) => piece.color == Color.Black && piece.pieceType == PieceType.Pawn }
      .map(_._1)

    val whitePawnsByFile = whitePawns.groupBy(_.file)
    val blackPawnsByFile = blackPawns.groupBy(_.file)

    val whiteDoubledPenalties =
      whitePawnsByFile.values.map(gps => if gps.size > 1 then (gps.size - 1) * 0.5 else 0.0).sum
    val blackDoubledPenalties =
      blackPawnsByFile.values.map(gps => if gps.size > 1 then (gps.size - 1) * 0.5 else 0.0).sum

    val whiteIsolatedPenalties = whitePawns.count { p =>
      !whitePawnsByFile.contains(p.file - 1) && !whitePawnsByFile.contains(p.file + 1)
    } * 0.5
    val blackIsolatedPenalties = blackPawns.count { p =>
      !blackPawnsByFile.contains(p.file - 1) && !blackPawnsByFile.contains(p.file + 1)
    } * 0.5

    val whiteKingPosOpt = piecesList.collectFirst {
      case (pos, Piece(Color.White, PieceType.King)) => pos
    }
    val blackKingPosOpt = piecesList.collectFirst {
      case (pos, Piece(Color.Black, PieceType.King)) => pos
    }

    val endgame = isEndgame(board)

    val baseScore = piecesList.foldLeft(0.0) { case (sum, (pos, piece)) =>
      val matValue = pieceValue(piece.pieceType)

      val idx =
        if piece.color == Color.White then pos.rank * 8 + pos.file
        else (7 - pos.rank) * 8 + pos.file

      val pstValue = piece.pieceType match
        case PieceType.Pawn   => pawnPST(idx)
        case PieceType.Knight => knightPST(idx)
        case PieceType.Bishop => bishopPST(idx)
        case PieceType.Rook   => rookPST(idx)
        case PieceType.Queen  => queenPST(idx)
        case PieceType.King   => if endgame then kingEndgamePST(idx) else kingMiddleGamePST(idx)

      val valSign = if piece.color == Color.White then 1.0 else -1.0
      sum + (matValue + pstValue) * valSign
    }

    val structureScore =
      baseScore - whiteDoubledPenalties + blackDoubledPenalties - whiteIsolatedPenalties + blackIsolatedPenalties

    if !endgame then
      val whiteShieldPenalty =
        whiteKingPosOpt.map(kp => kingShieldPenalty(board, kp, Color.White)).getOrElse(0.0)
      val blackShieldPenalty =
        blackKingPosOpt.map(kp => kingShieldPenalty(board, kp, Color.Black)).getOrElse(0.0)
      structureScore - whiteShieldPenalty + blackShieldPenalty
    else structureScore
=======
  private def evaluateBoard(board: Board): Double =
    board.piecesIterator.map { case (_, piece) =>
      val value = pieceValue(piece.pieceType)
      if piece.color == Color.White then value else -value
    }.sum
>>>>>>> 97d0df0b (added ai for lichess)

  private def pieceValue(pieceType: PieceType): Double =
    pieceType match
      case PieceType.Pawn   => 10.0
      case PieceType.Knight => 30.0
      case PieceType.Bishop => 30.0
      case PieceType.Rook   => 50.0
      case PieceType.Queen  => 90.0
      case PieceType.King   => 900.0

  private def parsePos(s: String): Position =
<<<<<<< HEAD
=======
    // e.g., "e2"
>>>>>>> 97d0df0b (added ai for lichess)
    val file = s.charAt(0) - 'a'
    val rank = s.charAt(1) - '1'
    Position.from(file, rank) match
      case Right(p) => p
<<<<<<< HEAD
      case Left(_) =>
        val Right(p) = Position.from(0, 0): @unchecked
        p
=======
      case Left(_) => throw new RuntimeException(s"Invalid position string: $s")
>>>>>>> 97d0df0b (added ai for lichess)

  private def parsePieceType(s: String): Option[PieceType] =
    s.toLowerCase match
      case "q" => Some(PieceType.Queen)
      case "r" => Some(PieceType.Rook)
      case "n" => Some(PieceType.Knight)
      case "b" => Some(PieceType.Bishop)
      case _   => None
