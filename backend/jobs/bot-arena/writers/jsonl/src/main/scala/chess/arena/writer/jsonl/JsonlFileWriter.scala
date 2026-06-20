package chess.arena.writer.jsonl

import chess.arena.events.{EventEmitter, GameEvent, GameEventJson}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

/** Appends one JSON line per event to a JSONL file.
  * Opens and closes the file on every emit — correct for Phase 1B validation.
  * TODO Phase 4: replace with a buffered writer held open for a tournament run,
  *   flushing after each GameFinished and closed via scala.util.Using (§14.4).
  */
final class JsonlFileWriter(path: Path) extends EventEmitter:
  def emit(event: GameEvent): Unit =
    val line = GameEventJson.encode(event) + "\n"
    Files.write(
      path,
      line.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.APPEND,
      StandardOpenOption.CREATE
    )
    ()
