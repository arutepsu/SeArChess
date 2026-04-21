package chess.adapter.event

/** Stable internal Game -> History downstream ingestion boundary metadata. */
object GameHistoryIngestionContract:
  val Version:     String = "game-history-ingestion-v1"
  val Audience:    String = "internal-downstream"
  val Interaction: String = "asynchronous-http-delivery"

  val GameEventsPath:       String = "/internal/events/game"

  /** Temporary compatibility alias. History Service keeps it disabled unless explicitly configured. */
  val LegacyGameEventsPath: String = "/events/game"
