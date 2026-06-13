package chess.arena.bots.uci

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{Executors, TimeUnit, TimeoutException}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.Using

final class ProcessUciEngine extends UciEngine:
  def bestMove(fen: String, config: UciBotConfig): Either[String, UciBestMove] =
    Using.Manager { use =>
      val process = use(UciProcess.start(config.enginePath))
      val session = use(UciEngineSession(process))
      session.initialize(config.startupTimeoutMillis).flatMap { _ =>
        session.bestMove(fen, config)
      }
    }.fold(e => Left(e.getMessage), identity)

private final class UciProcess private (process: Process) extends AutoCloseable:
  private val stdout = BufferedReader(
    InputStreamReader(process.getInputStream, StandardCharsets.UTF_8)
  )
  private val stdin = BufferedWriter(
    OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8)
  )

  def writeLine(command: String): Unit =
    stdin.write(command)
    stdin.newLine()
    stdin.flush()

  def readLine(): String = stdout.readLine()

  def close(): Unit =
    try writeLine("quit")
    catch case _: Throwable => ()
    if !process.waitFor(250L, TimeUnit.MILLISECONDS) then
      process.destroy()
      if !process.waitFor(250L, TimeUnit.MILLISECONDS) then process.destroyForcibly()
    ()

private object UciProcess:
  def start(enginePath: String): UciProcess =
    val process = ProcessBuilder(enginePath).redirectErrorStream(true).start()
    UciProcess(process)

private final class UciEngineSession(process: UciProcess) extends AutoCloseable:
  private val executor = Executors.newSingleThreadExecutor()
  given ExecutionContext = ExecutionContext.fromExecutor(executor)

  def initialize(timeoutMillis: Long): Either[String, Unit] =
    for
      _ <- sendAndWait("uci", "uciok", timeoutMillis)
      _ <- sendAndWait("isready", "readyok", timeoutMillis)
    yield ()

  def bestMove(fen: String, config: UciBotConfig): Either[String, UciBestMove] =
    buildGoCommand(config) match
      case Left(message) => Left(message)
      case Right(command) =>
        process.writeLine(s"position fen $fen")
        process.writeLine(command)
        readUntil(_.startsWith("bestmove "), config.moveTimeoutMillis)
          .flatMap(UciBestMove.parseLine)

  private def buildGoCommand(config: UciBotConfig): Either[String, String] =
    config.searchMode match
      case UciSearchMode.Depth =>
        config.depth
          .map(depth => s"go depth $depth")
          .toRight("UCI depth search requires depth")
      case UciSearchMode.MoveTime =>
        config.movetimeMillis
          .map(millis => s"go movetime $millis")
          .toRight("UCI movetime search requires movetimeMillis")

  private def sendAndWait(
      command: String,
      expectedLine: String,
      timeoutMillis: Long
  ): Either[String, Unit] =
    process.writeLine(command)
    readUntil(_ == expectedLine, timeoutMillis).map(_ => ())

  private def readUntil(matches: String => Boolean, timeoutMillis: Long): Either[String, String] =
    val future = Future {
      var line: String = process.readLine()
      while line != null && !matches(line.trim) do
        line = process.readLine()
      Option(line).map(_.trim)
    }

    try
      Await.result(future, Duration(timeoutMillis, TimeUnit.MILLISECONDS)) match
        case Some(line) => Right(line)
        case None       => Left("UCI engine closed stdout before expected response")
    catch
      case _: TimeoutException =>
        Left(s"Timed out after $timeoutMillis ms waiting for UCI engine response")

  def close(): Unit =
    executor.shutdownNow()
