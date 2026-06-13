package chess.arena.writer.kafka

import chess.arena.events.{EventEmitter, GameEvent}

final class CompositeEventEmitter(children: List[EventEmitter]) extends EventEmitter with AutoCloseable:
  def emit(event: GameEvent): Unit =
    children.foreach(_.emit(event))

  def close(): Unit =
    children.collect { case closeable: AutoCloseable => closeable }.foreach(_.close())

