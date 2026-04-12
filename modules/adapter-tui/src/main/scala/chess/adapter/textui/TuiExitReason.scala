package chess.adapter.textui

/** Describes why a [[TextUI]] session terminated. */
enum TuiExitReason:
  /** The user explicitly typed `quit`. */
  case UserQuit
  /** `readLine()` returned `null`: stdin is closed, redirected, or unavailable. */
  case EndOfInput
