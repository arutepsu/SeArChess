package chess.arena.bots.ai

import chess.adapter.ai.remote.{RemoteAiEngineSelection, RemoteAiLimits, RemoteAiMetadata, RemoteAiMoveSuggestionRequest}
import chess.arena.core.{BotPlayer, BotProfile}
import chess.arena.events.BotFamily
import chess.domain.model.{Color, Move}
import chess.domain.rules.GameStateRules
import chess.domain.state.GameState
import chess.notation.api.NotationFormat
import chess.notation.fen.FenNotationFacade
import java.util.UUID

final class SearchessAiBot(
    config: AiServiceBotConfig,
    client: SearchessAiClient
) extends BotPlayer:

  val profile: BotProfile = BotProfile(
    botId        = config.botId,
    family       = BotFamily.AiService,
    strategyType = "searchess-ai",
    engineType   = "none",
    modelVersion = config.modelVersion
  )

  def selectMove(state: GameState): Move =
    val result =
      for
        fen      <- exportFen(state)
        dtos      = GameStateRules.legalMoves(state).map(AiSquareNotation.moveToDto).toList
        _        <- if dtos.isEmpty then Left("No legal moves available") else Right(())
        request   = buildRequest(fen, dtos, state.currentPlayer)
        response <- client.suggestMove(request).left.map(_.describe)
        move     <- AiSquareNotation.dtoToLegalMove(response.move, state)
      yield move

    result.fold(msg => throw IllegalStateException(s"SearchessAiBot failed: $msg"), identity)

  private def buildRequest(
      fen: String,
      dtos: List[chess.adapter.ai.remote.RemoteAiMoveDto],
      player: Color
  ): RemoteAiMoveSuggestionRequest =
    RemoteAiMoveSuggestionRequest(
      requestId  = UUID.randomUUID().toString,
      gameId     = s"${config.gameIdPrefix}-${UUID.randomUUID()}",
      sessionId  = s"${config.gameIdPrefix}-${UUID.randomUUID()}",
      sideToMove = if player == Color.White then "white" else "black",
      fen        = fen,
      legalMoves = dtos,
      engine     = RemoteAiEngineSelection(config.engineId),
      limits     = RemoteAiLimits(config.timeoutMillis.toInt),
      metadata   = RemoteAiMetadata("BotArena")
    )

  private def exportFen(state: GameState): Either[String, String] =
    FenNotationFacade
      .executeExport(state, NotationFormat.FEN)
      .map(_.text)
      .left
      .map(_.toString)

object SearchessAiBot:
  def fromEnv(): SearchessAiBot =
    val config = AiServiceBotConfig.fromEnv()
    SearchessAiBot(config, HttpSearchessAiClient(config))
