package chess.streaming

import chess.domain.state.GameState as DomainGameState

sealed trait DslCommand:
  def lineNumber: Int

object DslCommand:
  final case class SessionStartedCommand(lineNumber: Int, sessionId: String) extends DslCommand
  final case class PlayersCommand(lineNumber: Int, whiteName: String, blackName: String) extends DslCommand
  final case class MoveCommand(lineNumber: Int, playerName: String, uciMove: String) extends DslCommand
  final case class StatusCommand(lineNumber: Int) extends DslCommand
  final case class ResignCommand(lineNumber: Int, playerName: String) extends DslCommand

final case class DslParseError(lineNumber: Int, rawInput: String, message: String)

final case class ValidationError(lineNumber: Int, rawInput: String, message: String)

final case class ValidatedCommand(command: DslCommand)

final case class GameStreamState(
    sessionId: Option[String],
    whiteName: Option[String],
    blackName: Option[String],
    gameState: DomainGameState,
    finished: Boolean,
    acceptedMoves: Int,
    rejectedMoves: Int
)

sealed trait GameProcessingResult:
  def lineNumber: Int
  def sessionId: Option[String]
  def message: String

object GameProcessingResult:
  final case class SessionStarted(lineNumber: Int, sessionIdValue: String) extends GameProcessingResult:
    override val sessionId: Option[String] = Some(sessionIdValue)
    override val message: String = s"session started: $sessionIdValue"

  final case class PlayersRegistered(
      lineNumber: Int,
      sessionIdValue: String,
      whiteName: String,
      blackName: String
  ) extends GameProcessingResult:
    override val sessionId: Option[String] = Some(sessionIdValue)
    override val message: String = s"players registered: $whiteName vs $blackName"

  final case class MoveAccepted(
      lineNumber: Int,
      sessionIdValue: String,
      playerName: String,
      uciMove: String,
      acceptedMoves: Int,
      rejectedMoves: Int,
      nextState: DomainGameState
  ) extends GameProcessingResult:
    override val sessionId: Option[String] = Some(sessionIdValue)
    override val message: String = s"move accepted: $playerName $uciMove"

  final case class MoveRejected(
      lineNumber: Int,
      sessionId: Option[String],
      playerName: String,
      uciMove: String,
      acceptedMoves: Int,
      rejectedMoves: Int,
      reason: String
  ) extends GameProcessingResult:
    override val message: String = s"move rejected: $playerName $uciMove ($reason)"

  final case class StatusSnapshot(
      lineNumber: Int,
      sessionId: Option[String],
      state: DomainGameState,
      acceptedMoves: Int,
      rejectedMoves: Int,
      finished: Boolean
  ) extends GameProcessingResult:
    override val message: String =
      s"status: ${state.status}, current player: ${state.currentPlayer}, accepted: $acceptedMoves, rejected: $rejectedMoves, finished: $finished"

  final case class GameResigned(
      lineNumber: Int,
      sessionId: Option[String],
      playerName: String,
      winnerName: Option[String]
  ) extends GameProcessingResult:
    override val message: String =
      winnerName.fold(s"$playerName resigned")(winner => s"$playerName resigned; winner: $winner")

  final case class GameFinished(
      lineNumber: Int,
      sessionId: Option[String],
      reason: String
  ) extends GameProcessingResult:
    override val message: String = s"game finished: $reason"

  final case class ParseFailed(error: DslParseError) extends GameProcessingResult:
    override val lineNumber: Int = error.lineNumber
    override val sessionId: Option[String] = None
    override val message: String = s"parse failed: ${error.message}"

  final case class ValidationFailed(error: ValidationError, sessionId: Option[String])
      extends GameProcessingResult:
    override val lineNumber: Int = error.lineNumber
    override val message: String = s"validation failed: ${error.message}"

final case class EventEnvelope(
    eventId: String,
    eventType: String,
    sessionId: Option[String],
    gameId: Option[String],
    sequenceNumber: Long,
    occurredAt: java.time.Instant,
    version: Int,
    payload: String
)

final case class StreamSummary(
    totalLines: Int,
    parsedCommands: Int,
    totalEvents: Int,
    acceptedMoves: Int,
    rejectedMoves: Int,
    parseFailures: Int,
    validationFailures: Int,
    finishedGames: Int
)

object StreamSummary:
  val empty: StreamSummary = StreamSummary(
    totalLines = 0,
    parsedCommands = 0,
    totalEvents = 0,
    acceptedMoves = 0,
    rejectedMoves = 0,
    parseFailures = 0,
    validationFailures = 0,
    finishedGames = 0
  )

  def fromEnvelopes(envelopes: Iterable[EventEnvelope]): StreamSummary =
    envelopes.foldLeft(empty) { (summary, envelope) =>
      envelope.eventType match
        case "SessionStarted" | "PlayersRegistered" | "StatusSnapshot" | "GameResigned" =>
          summary.copy(
            totalLines = summary.totalLines + 1,
            parsedCommands = summary.parsedCommands + 1,
            totalEvents = summary.totalEvents + 1
          )
        case "MoveAccepted" =>
          summary.copy(
            totalLines = summary.totalLines + 1,
            parsedCommands = summary.parsedCommands + 1,
            totalEvents = summary.totalEvents + 1,
            acceptedMoves = summary.acceptedMoves + 1
          )
        case "MoveRejected" =>
          summary.copy(
            totalLines = summary.totalLines + 1,
            parsedCommands = summary.parsedCommands + 1,
            totalEvents = summary.totalEvents + 1,
            rejectedMoves = summary.rejectedMoves + 1
          )
        case "ParseFailed" =>
          summary.copy(
            totalLines = summary.totalLines + 1,
            totalEvents = summary.totalEvents + 1,
            parseFailures = summary.parseFailures + 1
          )
        case "ValidationFailed" =>
          summary.copy(
            totalLines = summary.totalLines + 1,
            parsedCommands = summary.parsedCommands + 1,
            totalEvents = summary.totalEvents + 1,
            validationFailures = summary.validationFailures + 1
          )
        case "GameFinished" =>
          summary.copy(totalEvents = summary.totalEvents + 1, finishedGames = summary.finishedGames + 1)
        case _ =>
          summary.copy(totalEvents = summary.totalEvents + 1)
    }
