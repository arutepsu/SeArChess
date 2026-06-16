package chess.arena.events

trait EventEmitter:
  def emit(event: GameEvent): Unit
