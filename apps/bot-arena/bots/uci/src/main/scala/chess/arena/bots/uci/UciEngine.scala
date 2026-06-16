package chess.arena.bots.uci

trait UciEngine:
  def bestMove(fen: String, config: UciBotConfig): Either[String, UciBestMove]

object UciEngine:
  val processBacked: UciEngine = new ProcessUciEngine
