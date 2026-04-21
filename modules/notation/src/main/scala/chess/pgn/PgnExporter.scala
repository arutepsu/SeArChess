package chess.notation.pgn

import chess.domain.model.{Color, GameStatus}
import chess.domain.rules.GameStateRules
import chess.domain.state.{GameState, GameStateFactory}
import chess.notation.api._

/** Serialises a [[GameState]] to a PGN movetext string.
  *
  * Export scope (Phase 4):
  *   - standard initial position only (games started with a non-standard position cannot be
  *     exported and return [[ExportFailure.UnsupportedExportFormat]])
  *   - mainline move history only
  *   - no headers — `GameState` carries no game-metadata, so none is emitted
  *   - result token derived from `GameState.status`
  *
  * Explicitly deferred: PGN headers, comments, variations, NAGs.
  *
  * Visible only within the `chess.notation.pgn` package.
  */
private[pgn] object PgnExporter extends NotationExporter[GameState]:

  override def exportNotation(
      data: GameState,
      format: NotationFormat
  ): Either[NotationFailure, ExportResult] =
    format match
      case NotationFormat.PGN => exportAsPgn(data)
      case other =>
        Left(
          ExportFailure.UnsupportedExportFormat(
            other,
            s"PgnExporter does not support format: $other"
          )
        )

  // ── Export pipeline ─────────────────────────────────────────────────────────

  private def exportAsPgn(state: GameState): Either[NotationFailure, ExportResult] =
    renderMoveHistory(state).map { sanTokens =>
      val moveText = formatMoveText(sanTokens)
      val result = resultToken(state.status)
      val text = if moveText.isEmpty then result else s"$moveText $result"
      ExportResult(text = text, format = NotationFormat.PGN)
    }

  /** Replay `state.moveHistory` from the standard initial position, collecting one SAN token per
    * move. Fails on the first move that cannot be rendered.
    */
  private def renderMoveHistory(state: GameState): Either[NotationFailure, Vector[String]] =
    state.moveHistory
      .foldLeft[Either[NotationFailure, (GameState, Vector[String])]](
        Right((GameStateFactory.initial(), Vector.empty))
      ) {
        case (Left(err), _) => Left(err)
        case (Right((current, tokens)), move) =>
          SanRenderer.render(current, move).flatMap { san =>
            GameStateRules
              .applyMove(current, move)
              .left
              .map { err =>
                ExportFailure.SerializationError(
                  "move",
                  s"Cannot replay move $move during export: $err"
                )
              }
              .map { next =>
                (next, tokens :+ san)
              }
          }
      }
      .map(_._2)

  // ── Formatting helpers ───────────────────────────────────────────────────────

  /** Format SAN tokens as `1. e4 e5 2. Nf3 Nc6 ...` White's move starts with a move number; Black's
    * move follows without one.
    */
  private def formatMoveText(tokens: Vector[String]): String =
    tokens.zipWithIndex
      .map { case (san, i) =>
        if i % 2 == 0 then s"${i / 2 + 1}. $san" else san
      }
      .mkString(" ")

  private def resultToken(status: GameStatus): String = status match
    case GameStatus.Checkmate(Color.White) => "1-0"
    case GameStatus.Checkmate(Color.Black) => "0-1"
    case GameStatus.Draw(_)                => "1/2-1/2"
    case GameStatus.Ongoing(_)             => "*"
    case GameStatus.Resigned(Color.White)  => "1-0"
    case GameStatus.Resigned(Color.Black)  => "0-1"
