package chess.adapter.http4s.mapper

import chess.adapter.rest.contract.dto.*
import chess.application.query.game.{GameArchiveSnapshot, GameClosure}
import chess.application.session.model.SideController
import chess.domain.model.Color

object ArchiveSnapshotMapper:

  def toResponse(snapshot: GameArchiveSnapshot): ArchiveSnapshotResponse =
    ArchiveSnapshotResponse(
      sessionId = snapshot.sessionId.value.toString,
      gameId = snapshot.gameId.value.toString,
      mode = snapshot.mode.toString,
      whiteController = controllerString(snapshot.whiteController),
      blackController = controllerString(snapshot.blackController),
      closure = closureResponse(snapshot.closure),
      finalState = ArchiveGameStateResponse(
        game = GameMapper.toGameResponse(snapshot.finalState),
        castlingRights = CastlingRightsDto(
          whiteKingSide = snapshot.finalState.castlingRights.whiteKingSide,
          whiteQueenSide = snapshot.finalState.castlingRights.whiteQueenSide,
          blackKingSide = snapshot.finalState.castlingRights.blackKingSide,
          blackQueenSide = snapshot.finalState.castlingRights.blackQueenSide
        ),
        enPassant = snapshot.finalState.enPassantState.map(ep =>
          EnPassantDto(
            targetSquare = ep.targetSquare.toString,
            capturablePawnSquare = ep.capturablePawnSquare.toString,
            pawnColor = ep.pawnColor.toString
          )
        )
      ),
      createdAt = snapshot.createdAt.toString,
      closedAt = snapshot.closedAt.toString
    )

  private def closureResponse(closure: GameClosure): ArchiveClosureResponse = closure match
    case GameClosure.Checkmate(winner) =>
      ArchiveClosureResponse("Checkmate", Some(colorString(winner)), None)
    case GameClosure.Resigned(winner) =>
      ArchiveClosureResponse("Resigned", Some(colorString(winner)), None)
    case GameClosure.Draw(reason) =>
      ArchiveClosureResponse("Draw", None, Some(reason.toString))
    case GameClosure.Cancelled =>
      ArchiveClosureResponse("Cancelled", None, None)

  private def colorString(color: Color): String = color match
    case Color.White => "White"
    case Color.Black => "Black"

  private def controllerString(controller: SideController): String = controller match
    case SideController.HumanLocal       => "HumanLocal"
    case SideController.HumanRemote      => "HumanRemote"
    case SideController.AI(Some(engine)) => s"AI:$engine"
    case SideController.AI(None)         => "AI"
