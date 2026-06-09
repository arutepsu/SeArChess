package chess.streaming

import chess.domain.model.{Color, GameStatus, Move as DomainMove, PieceType, Position}
import chess.domain.rules.GameStateRules
import chess.domain.state.GameStateFactory
import chess.streaming.DslCommand.*
import chess.streaming.GameProcessingResult.*
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{IOResult, OverflowStrategy}
import org.apache.pekko.stream.scaladsl.{FileIO, Flow, Framing, Sink, Source, StreamConverters}
import org.apache.pekko.util.ByteString

import java.nio.file.{Path, Paths}
import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration.*

object SearchessReactiveStreams:
  private val DefaultResource = "searchess-game.dsl"
  private val MaximumLineLength = 1024
  private val BatchSize = 5
  private val BatchWithin = 500.millis
  private val BufferSize = 32
  private val EnvelopeVersion = 1

  private type ProcessingInput = Either[GameProcessingResult, ValidatedCommand]

  def sourceFromPath(path: Path): Source[String, Future[IOResult]] =
    FileIO
      .fromPath(path)
      .via(Framing.delimiter(ByteString("\n"), MaximumLineLength, allowTruncation = true))
      .map(_.utf8String.stripSuffix("\r"))

  def sourceFromResource(resourceName: String): Source[String, Future[IOResult]] =
    StreamConverters
      .fromInputStream { () =>
        Option(getClass.getClassLoader.getResourceAsStream(resourceName))
          .getOrElse(throw IllegalStateException(s"default DSL resource not found: $resourceName"))
      }
      .via(Framing.delimiter(ByteString("\n"), MaximumLineLength, allowTruncation = true))
      .map(_.utf8String.stripSuffix("\r"))

  def sourceFromArgs(args: Array[String]): Source[String, ?] =
    args.headOption match
      case Some(path) => sourceFromPath(Paths.get(path))
      case None => sourceFromResource(DefaultResource)

  val parseDslFlow: Flow[String, Either[DslParseError, DslCommand], NotUsed] =
    Flow[String]
      .zipWithIndex
      .mapConcat { case (line, index) =>
        SearchessDslParser.parseLine(line, index.toInt + 1).toList
      }

  val validateCommandFlow: Flow[DslCommand, Either[ValidationError, ValidatedCommand], NotUsed] =
    Flow[DslCommand].statefulMapConcat { () =>
      var sessionSeen = false
      var playersSeen = false
      var players: Set[String] = Set.empty

      {
        case command @ SessionStartedCommand(_, id) =>
          if sessionSeen then
            List(Left(ValidationError(command.lineNumber, s"session $id", "session command may only appear once")))
          else
            sessionSeen = true
            List(Right(ValidatedCommand(command)))

        case command @ PlayersCommand(_, whiteName, blackName) =>
          if !sessionSeen then
            List(Left(ValidationError(command.lineNumber, s"players $whiteName $blackName", "players require a session first")))
          else if playersSeen then
            List(Left(ValidationError(command.lineNumber, s"players $whiteName $blackName", "players command may only appear once")))
          else if whiteName == blackName then
            List(Left(ValidationError(command.lineNumber, s"players $whiteName $blackName", "players must be distinct")))
          else
            playersSeen = true
            players = Set(whiteName, blackName)
            List(Right(ValidatedCommand(command)))

        case command @ MoveCommand(_, playerName, uciMove) =>
          if !sessionSeen then
            List(Left(ValidationError(command.lineNumber, s"move $playerName $uciMove", "moves require a session first")))
          else if !playersSeen then
            List(Left(ValidationError(command.lineNumber, s"move $playerName $uciMove", "moves require registered players")))
          else if !isUciMove(uciMove) then
            List(Left(ValidationError(command.lineNumber, s"move $playerName $uciMove", "move must use UCI notation")))
          else if !players.contains(playerName) then
            List(Left(ValidationError(command.lineNumber, s"move $playerName $uciMove", s"unknown player: $playerName")))
          else List(Right(ValidatedCommand(command)))

        case command @ StatusCommand(_) =>
          if !sessionSeen then
            List(Left(ValidationError(command.lineNumber, "status", "status requires a session first")))
          else List(Right(ValidatedCommand(command)))

        case command @ ResignCommand(_, playerName) =>
          if !playersSeen then
            List(Left(ValidationError(command.lineNumber, s"resign $playerName", "resign requires registered players")))
          else if !players.contains(playerName) then
            List(Left(ValidationError(command.lineNumber, s"resign $playerName", s"unknown player: $playerName")))
          else List(Right(ValidatedCommand(command)))
      }
    }

  val parsedValidationFlow: Flow[Either[DslParseError, DslCommand], ProcessingInput, NotUsed] =
    Flow[Either[DslParseError, DslCommand]].statefulMapConcat { () =>
      val validation = validateOneCommand()
      {
        case Left(parseError) =>
          List(Left(ParseFailed(parseError)))
        case Right(command) =>
          validation(command).map {
            case Left(error) => Left(ValidationFailed(error, currentSessionFrom(command)))
            case Right(validated) => Right(validated)
          }
      }
    }

  val processGameFlow: Flow[ValidatedCommand, GameProcessingResult, NotUsed] =
    Flow[ValidatedCommand].statefulMapConcat { () =>
      var state = GameStreamState(
        sessionId = None,
        whiteName = None,
        blackName = None,
        gameState = GameStateFactory.initial(),
        finished = false,
        acceptedMoves = 0,
        rejectedMoves = 0
      )

      {
        case ValidatedCommand(SessionStartedCommand(lineNumber, sessionId)) =>
          state = state.copy(sessionId = Some(sessionId))
          List(SessionStarted(lineNumber, sessionId))

        case ValidatedCommand(PlayersCommand(lineNumber, whiteName, blackName)) =>
          state = state.copy(whiteName = Some(whiteName), blackName = Some(blackName))
          List(PlayersRegistered(lineNumber, state.sessionId.getOrElse("unknown-session"), whiteName, blackName))

        case ValidatedCommand(StatusCommand(lineNumber)) =>
          List(StatusSnapshot(lineNumber, state.sessionId, state.gameState, state.acceptedMoves, state.rejectedMoves, state.finished))

        case ValidatedCommand(command @ MoveCommand(_, _, _)) =>
          processMove(state, command) match
            case Right((nextState, results)) =>
              state = nextState
              results
            case Left(result) =>
              state = state.copy(rejectedMoves = state.rejectedMoves + 1)
              List(result)

        case ValidatedCommand(ResignCommand(lineNumber, playerName)) =>
          val winnerName = winnerAfterResignation(state, playerName)
          val winnerColor = colorForPlayer(state, winnerName.getOrElse("")).getOrElse(Color.White)
          state = state.copy(
            gameState = state.gameState.copy(status = GameStatus.Resigned(winnerColor)),
            finished = true
          )
          List(
            GameResigned(lineNumber, state.sessionId, playerName, winnerName),
            GameFinished(lineNumber, state.sessionId, s"$playerName resigned")
          )
      }
    }

  val processingInputFlow: Flow[ProcessingInput, GameProcessingResult, NotUsed] =
    Flow[ProcessingInput].statefulMapConcat { () =>
      val process = processOneValidatedCommand()
      {
        case Left(result) => List(result)
        case Right(validated) => process(validated)
      }
    }

  val eventEnvelopeFlow: Flow[GameProcessingResult, EventEnvelope, NotUsed] =
    Flow[GameProcessingResult].statefulMapConcat { () =>
      var sequenceNumber = 0L
      result =>
        sequenceNumber = sequenceNumber + 1
        List(EventEnvelope(
          eventId = s"searchess-${sequenceNumber}",
          eventType = result.getClass.getSimpleName.stripSuffix("$"),
          sessionId = result.sessionId,
          gameId = result.sessionId,
          sequenceNumber = sequenceNumber,
          occurredAt = Instant.now(),
          version = EnvelopeVersion,
          payload = result.message
        ))
    }

  val backpressureBufferFlow: Flow[EventEnvelope, EventEnvelope, NotUsed] =
    Flow[EventEnvelope].buffer(BufferSize, OverflowStrategy.backpressure)

  val batchBackpressureFlow: Flow[EventEnvelope, Seq[EventEnvelope], NotUsed] =
    Flow[EventEnvelope].groupedWithin(BatchSize, BatchWithin)

  val recoverNonFatalFlow: Flow[EventEnvelope, EventEnvelope, NotUsed] =
    Flow[EventEnvelope].recover { case error =>
      EventEnvelope(
        eventId = "searchess-recovered-error",
        eventType = "StreamRecovered",
        sessionId = None,
        gameId = None,
        sequenceNumber = -1L,
        occurredAt = Instant.now(),
        version = EnvelopeVersion,
        payload = s"stream recovered from: ${error.getMessage}"
      )
    }

  val failFastFlow: Flow[EventEnvelope, EventEnvelope, NotUsed] =
    Flow[EventEnvelope].map { envelope =>
      if envelope.eventType == "FatalStreamError" then
        throw IllegalStateException(envelope.payload)
      else envelope
    }

  val consoleSink: Sink[Seq[EventEnvelope], Future[StreamSummary]] =
    Sink.fold(StreamSummary.empty) { (summary, batch) =>
      println(s"[batch] size=${batch.size}")
      batch.foreach { envelope =>
        println(
          s"${envelope.sequenceNumber} ${envelope.eventType} " +
            s"session=${envelope.sessionId.getOrElse("-")} ${envelope.payload}"
        )
      }
      mergeSummaries(summary, StreamSummary.fromEnvelopes(batch))
    }

  val summarySink: Sink[Seq[EventEnvelope], Future[StreamSummary]] =
    Sink.fold(StreamSummary.empty) { (summary, batch) =>
      mergeSummaries(summary, StreamSummary.fromEnvelopes(batch))
    }

  def jsonLinesFileSink(path: Path): Sink[Seq[EventEnvelope], Future[IOResult]] =
    Flow[Seq[EventEnvelope]]
      .mapConcat(_.toList)
      .map(envelope => ByteString(envelopeToJson(envelope) + System.lineSeparator()))
      .toMat(FileIO.toPath(path))(org.apache.pekko.stream.scaladsl.Keep.right)

  def run(args: Array[String]): Source[Seq[EventEnvelope], ?] =
    sourceFromArgs(args)
      .via(parseDslFlow)
      .via(parsedValidationFlow)
      .via(processingInputFlow)
      .via(eventEnvelopeFlow)
      .via(backpressureBufferFlow)
      .via(failFastFlow)
      .via(recoverNonFatalFlow)
      .via(batchBackpressureFlow)

  private def processMove(
      state: GameStreamState,
      command: MoveCommand
  ): Either[GameProcessingResult, (GameStreamState, List[GameProcessingResult])] =
    if state.finished then
      Left(MoveRejected(
        command.lineNumber,
        state.sessionId,
        command.playerName,
        command.uciMove,
        state.acceptedMoves,
        state.rejectedMoves + 1,
        "game is already finished"
      ))
    else
      val expectedPlayer = playerForColor(state, state.gameState.currentPlayer)
      if !expectedPlayer.contains(command.playerName) then
        Left(MoveRejected(
          command.lineNumber,
          state.sessionId,
          command.playerName,
          command.uciMove,
          state.acceptedMoves,
          state.rejectedMoves + 1,
          s"expected ${expectedPlayer.getOrElse(state.gameState.currentPlayer.toString)} to move"
        ))
      else
        parseDomainMove(command.uciMove) match
          case Left(reason) =>
            Left(MoveRejected(command.lineNumber, state.sessionId, command.playerName, command.uciMove, state.acceptedMoves, state.rejectedMoves + 1, reason))
          case Right(move) =>
            GameStateRules.applyMove(state.gameState, move) match
              case Left(error) =>
                Left(MoveRejected(
                  command.lineNumber,
                  state.sessionId,
                  command.playerName,
                  command.uciMove,
                  state.acceptedMoves,
                  state.rejectedMoves + 1,
                  error.toString
                ))
              case Right(nextGameState) =>
                val accepted = MoveAccepted(
                  command.lineNumber,
                  state.sessionId.getOrElse("unknown-session"),
                  command.playerName,
                  command.uciMove,
                  state.acceptedMoves + 1,
                  state.rejectedMoves,
                  nextGameState
                )
                val finishedResult = nextGameState.status match
                  case GameStatus.Ongoing(_) => Nil
                  case status => List(GameFinished(command.lineNumber, state.sessionId, status.toString))
                val nextState = state.copy(
                  gameState = nextGameState,
                  acceptedMoves = state.acceptedMoves + 1,
                  finished = finishedResult.nonEmpty
                )
                Right(nextState -> (accepted :: finishedResult))

  private def parseDomainMove(uciMove: String): Either[String, DomainMove] =
    val from = uciMove.take(2)
    val to = uciMove.slice(2, 4)
    for
      promotion <- parsePromotion(uciMove.drop(4).headOption)
      fromPosition <- Position.fromAlgebraic(from).left.map(_.toString)
      toPosition <- Position.fromAlgebraic(to).left.map(_.toString)
    yield DomainMove(fromPosition, toPosition, promotion)

  private def parsePromotion(input: Option[Char]): Either[String, Option[PieceType]] =
    input match
      case None => Right(None)
      case Some('q') => Right(Some(PieceType.Queen))
      case Some('r') => Right(Some(PieceType.Rook))
      case Some('b') => Right(Some(PieceType.Bishop))
      case Some('n') => Right(Some(PieceType.Knight))
      case Some(other) => Left(s"unsupported promotion piece: $other")

  private def playerForColor(state: GameStreamState, color: Color): Option[String] =
    color match
      case Color.White => state.whiteName
      case Color.Black => state.blackName

  private def colorForPlayer(state: GameStreamState, playerName: String): Option[Color] =
    if state.whiteName.contains(playerName) then Some(Color.White)
    else if state.blackName.contains(playerName) then Some(Color.Black)
    else None

  private def winnerAfterResignation(state: GameStreamState, playerName: String): Option[String] =
    colorForPlayer(state, playerName).flatMap(color => playerForColor(state, color.opposite))

  private def mergeSummaries(left: StreamSummary, right: StreamSummary): StreamSummary =
    StreamSummary(
      totalLines = left.totalLines + right.totalLines,
      parsedCommands = left.parsedCommands + right.parsedCommands,
      totalEvents = left.totalEvents + right.totalEvents,
      acceptedMoves = left.acceptedMoves + right.acceptedMoves,
      rejectedMoves = left.rejectedMoves + right.rejectedMoves,
      parseFailures = left.parseFailures + right.parseFailures,
      validationFailures = left.validationFailures + right.validationFailures,
      finishedGames = left.finishedGames + right.finishedGames
    )

  private def isUciMove(value: String): Boolean =
    value.matches("^[a-h][1-8][a-h][1-8][qrbn]?$")

  private def currentSessionFrom(command: DslCommand): Option[String] =
    command match
      case SessionStartedCommand(_, sessionId) => Some(sessionId)
      case _ => None

  private def validateOneCommand(): DslCommand => List[Either[ValidationError, ValidatedCommand]] =
    var sessionSeen = false
    var playersSeen = false
    var players: Set[String] = Set.empty

    command =>
      command match
        case SessionStartedCommand(_, id) =>
          if sessionSeen then List(Left(ValidationError(command.lineNumber, s"session $id", "session command may only appear once")))
          else
            sessionSeen = true
            List(Right(ValidatedCommand(command)))
        case PlayersCommand(_, whiteName, blackName) =>
          if !sessionSeen then List(Left(ValidationError(command.lineNumber, s"players $whiteName $blackName", "players require a session first")))
          else if playersSeen then List(Left(ValidationError(command.lineNumber, s"players $whiteName $blackName", "players command may only appear once")))
          else
            playersSeen = true
            players = Set(whiteName, blackName)
            List(Right(ValidatedCommand(command)))
        case MoveCommand(_, playerName, uciMove) =>
          if !sessionSeen then List(Left(ValidationError(command.lineNumber, s"move $playerName $uciMove", "moves require a session first")))
          else if !playersSeen then List(Left(ValidationError(command.lineNumber, s"move $playerName $uciMove", "moves require registered players")))
          else if !isUciMove(uciMove) then List(Left(ValidationError(command.lineNumber, s"move $playerName $uciMove", "move must use UCI notation")))
          else if !players.contains(playerName) then List(Left(ValidationError(command.lineNumber, s"move $playerName $uciMove", s"unknown player: $playerName")))
          else List(Right(ValidatedCommand(command)))
        case StatusCommand(_) =>
          if !sessionSeen then List(Left(ValidationError(command.lineNumber, "status", "status requires a session first")))
          else List(Right(ValidatedCommand(command)))
        case ResignCommand(_, playerName) =>
          if !playersSeen then List(Left(ValidationError(command.lineNumber, s"resign $playerName", "resign requires registered players")))
          else if !players.contains(playerName) then List(Left(ValidationError(command.lineNumber, s"resign $playerName", s"unknown player: $playerName")))
          else List(Right(ValidatedCommand(command)))

  private def processOneValidatedCommand(): ValidatedCommand => List[GameProcessingResult] =
    var state = GameStreamState(None, None, None, GameStateFactory.initial(), finished = false, acceptedMoves = 0, rejectedMoves = 0)

    validated =>
      validated.command match
        case SessionStartedCommand(lineNumber, sessionId) =>
          state = state.copy(sessionId = Some(sessionId))
          List(SessionStarted(lineNumber, sessionId))
        case PlayersCommand(lineNumber, whiteName, blackName) =>
          state = state.copy(whiteName = Some(whiteName), blackName = Some(blackName))
          List(PlayersRegistered(lineNumber, state.sessionId.getOrElse("unknown-session"), whiteName, blackName))
        case StatusCommand(lineNumber) =>
          List(StatusSnapshot(lineNumber, state.sessionId, state.gameState, state.acceptedMoves, state.rejectedMoves, state.finished))
        case command @ MoveCommand(_, _, _) =>
          processMove(state, command) match
            case Right((nextState, results)) =>
              state = nextState
              results
            case Left(result) =>
              state = state.copy(rejectedMoves = state.rejectedMoves + 1)
              List(result)
        case ResignCommand(lineNumber, playerName) =>
          val winnerName = winnerAfterResignation(state, playerName)
          val winnerColor = colorForPlayer(state, winnerName.getOrElse("")).getOrElse(Color.White)
          state = state.copy(
            gameState = state.gameState.copy(status = GameStatus.Resigned(winnerColor)),
            finished = true
          )
          List(
            GameResigned(lineNumber, state.sessionId, playerName, winnerName),
            GameFinished(lineNumber, state.sessionId, s"$playerName resigned")
          )

  def envelopeToJson(envelope: EventEnvelope): String =
    ujson.write(ujson.Obj(
      "eventId" -> envelope.eventId,
      "eventType" -> envelope.eventType,
      "sessionId" -> envelope.sessionId.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
      "gameId" -> envelope.gameId.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
      "sequenceNumber" -> envelope.sequenceNumber,
      "occurredAt" -> envelope.occurredAt.toString,
      "version" -> envelope.version,
      "payload" -> envelope.payload
    ))
