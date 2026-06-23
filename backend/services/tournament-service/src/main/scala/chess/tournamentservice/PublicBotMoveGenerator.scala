package chess.tournamentservice

import chess.arena.core.{BotPlayer, MoveUci}
import chess.domain.state.GameState
import chess.notation.api.{ImportResult, ImportTarget, NotationFormat}
import chess.notation.fen.FenNotationFacade
import scala.util.Try

/** Generates a UCI move for a given Searchess bot from a live public tournament game snapshot.
  *
  * This is a pure adapter: it parses the FEN from the snapshot into a [[GameState]], delegates to
  * the bot's [[BotPlayer.selectMove]], and encodes the result as a UCI string. It does not submit
  * the move, does not poll for games, and does not know any tournament-server credentials.
  *
  * Caller responsibilities (not enforced here):
  *  - Verify the bot is a participant in the tournament (via user-service).
  *  - Verify it is the bot's turn (active color from FEN matches the bot's side).
  *  - Resolve searchessBotId → BotPlayer via [[BotRegistry]] before calling [[generate]].
  *  - Submit the returned UCI move via the gateway's internal move endpoint.
  */
object PublicBotMoveGenerator:

  private val GameOverStatuses: Set[String] =
    Set("finished", "draw", "stalemate", "resign", "resigned", "aborted", "abort")

  def generate(
    bot:      BotPlayer,
    snapshot: PublicGameSnapshot
  ): Either[MoveGenerationError, String] =
    for
      _     <- Either.cond(
                 !isGameOver(snapshot.status),
                 (),
                 MoveGenerationError.GameNotOngoing(snapshot.status)
               )
      state <- parseFen(snapshot.fen)
      move  <- Try(bot.selectMove(state)).toEither
                 .left.map(e => MoveGenerationError.BotFailed(Option(e.getMessage).getOrElse("unknown")))
    yield MoveUci.encode(move)

  private def isGameOver(status: String): Boolean =
    GameOverStatuses.contains(status.toLowerCase.trim)

  private def parseFen(fen: String): Either[MoveGenerationError, GameState] =
    FenNotationFacade.parseAndImport(NotationFormat.FEN, fen, ImportTarget.PositionTarget) match
      case Right(r: ImportResult.PositionImportResult[GameState]) => Right(r.data)
      case Right(r: ImportResult.GameImportResult[GameState])     => Right(r.data)
      case Right(_)  => Left(MoveGenerationError.InvalidFen(fen, "Unexpected FEN import result type"))
      case Left(err) => Left(MoveGenerationError.InvalidFen(fen, err.toString))
