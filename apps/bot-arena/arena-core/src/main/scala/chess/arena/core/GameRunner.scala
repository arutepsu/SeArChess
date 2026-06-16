package chess.arena.core

import chess.arena.events.{BotFamily, EventEmitter, GameFinished, GameStarted, MovePlayed}
import chess.domain.model.{Color, GameStatus}
import chess.domain.rules.GameStateRules
import chess.domain.state.{GameState, GameStateFactory}
import java.time.Instant
import java.util.UUID
import scala.annotation.tailrec

final class GameRunner(
    white: BotPlayer,
    black: BotPlayer,
    emitter: EventEmitter,
    tournamentId: String,
    maxPly: Int = 500
):

  def run(gameId: String): Unit =
    val gameStart = System.currentTimeMillis()
    emitter.emit(makeGameStarted(gameId))

    @tailrec
    def loop(state: GameState, plyCount: Int): Unit =
      state.status match
        case GameStatus.Checkmate(winner) =>
          emitter.emit(makeGameFinished(
            gameId, plyCount, gameStart,
            result = colorToResult(winner),
            winnerBotId = Some(botForColor(winner).profile.botId),
            loserBotId  = Some(botForColor(winner.opposite).profile.botId),
            terminationReason = "checkmate"
          ))

        case GameStatus.Draw(_) =>
          emitter.emit(makeGameFinished(
            gameId, plyCount, gameStart,
            result = "draw", winnerBotId = None, loserBotId = None,
            terminationReason = "stalemate"
          ))

        case GameStatus.Resigned(winner) =>
          emitter.emit(makeGameFinished(
            gameId, plyCount, gameStart,
            result = colorToResult(winner),
            winnerBotId = Some(botForColor(winner).profile.botId),
            loserBotId  = Some(botForColor(winner.opposite).profile.botId),
            terminationReason = "resigned"
          ))

        case GameStatus.Ongoing(_) =>
          if state.halfmoveClock >= 100 then
            emitter.emit(makeGameFinished(
              gameId, plyCount, gameStart,
              result = "draw", winnerBotId = None, loserBotId = None,
              terminationReason = "50-move-rule"
            ))
          else if plyCount >= maxPly then
            emitter.emit(makeGameFinished(
              gameId, plyCount, gameStart,
              result = "draw", winnerBotId = None, loserBotId = None,
              terminationReason = "max-ply"
            ))
          else
            val bot    = botForColor(state.currentPlayer)
            val tStart = System.currentTimeMillis()
            val move   = bot.selectMove(state)
            val dur    = System.currentTimeMillis() - tStart
            GameStateRules.applyMove(state, move) match
              case Left(_) =>
                val losingColor  = state.currentPlayer
                val winningColor = losingColor.opposite
                emitter.emit(makeGameFinished(
                  gameId, plyCount, gameStart,
                  result = colorToResult(winningColor),
                  winnerBotId = Some(botForColor(winningColor).profile.botId),
                  loserBotId  = Some(botForColor(losingColor).profile.botId),
                  terminationReason = "illegal-move"
                ))
              case Right(nextState) =>
                emitter.emit(makeMovePlayed(gameId, plyCount + 1, bot.profile.botId, move, dur))
                loop(nextState, plyCount + 1)

    loop(GameStateFactory.initial(), 0)

  private def botForColor(color: Color): BotPlayer =
    if color == Color.White then white else black

  private def colorToResult(winner: Color): String =
    if winner == Color.White then "white" else "black"

  private def makeGameStarted(gameId: String): GameStarted =
    GameStarted(
      eventId           = UUID.randomUUID().toString,
      timestamp         = Instant.now().toString,
      tournamentId      = tournamentId,
      gameId            = gameId,
      whiteBotId        = white.profile.botId,
      blackBotId        = black.profile.botId,
      whiteBotFamily    = white.profile.family.toJsonString,
      blackBotFamily    = black.profile.family.toJsonString,
      whiteStrategyType = white.profile.strategyType,
      blackStrategyType = black.profile.strategyType,
      whiteEngineType   = white.profile.engineType,
      blackEngineType   = black.profile.engineType,
      whiteModelVersion = white.profile.modelVersion,
      blackModelVersion = black.profile.modelVersion
    )

  private def makeMovePlayed(
      gameId: String,
      plyNumber: Int,
      botId: String,
      move: chess.domain.model.Move,
      dur: Long
  ): MovePlayed =
    MovePlayed(
      eventId        = UUID.randomUUID().toString,
      timestamp      = Instant.now().toString,
      tournamentId   = tournamentId,
      gameId         = gameId,
      botId          = botId,
      moveUci        = MoveUci.encode(move),
      plyNumber      = plyNumber,
      durationMillis = dur
    )

  private def makeGameFinished(
      gameId: String,
      totalPly: Int,
      gameStart: Long,
      result: String,
      winnerBotId: Option[String],
      loserBotId: Option[String],
      terminationReason: String
  ): GameFinished =
    GameFinished(
      eventId           = UUID.randomUUID().toString,
      timestamp         = Instant.now().toString,
      tournamentId      = tournamentId,
      gameId            = gameId,
      whiteBotId        = white.profile.botId,
      blackBotId        = black.profile.botId,
      winnerBotId       = winnerBotId,
      loserBotId        = loserBotId,
      result            = result,
      whiteBotFamily    = white.profile.family.toJsonString,
      blackBotFamily    = black.profile.family.toJsonString,
      whiteStrategyType = white.profile.strategyType,
      blackStrategyType = black.profile.strategyType,
      whiteEngineType   = white.profile.engineType,
      blackEngineType   = black.profile.engineType,
      whiteModelVersion = white.profile.modelVersion,
      blackModelVersion = black.profile.modelVersion,
      totalMoves        = (totalPly + 1) / 2,
      totalPly          = totalPly,
      durationMillis    = System.currentTimeMillis() - gameStart,
      terminationReason = terminationReason
    )
