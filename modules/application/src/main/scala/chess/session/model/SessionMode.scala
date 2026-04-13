package chess.application.session.model

/** Describes the match configuration between the two sides.
 *
 *  This is an orchestration-level concept: it tells the session layer how to
 *  route turn decisions, but it carries no chess-rule knowledge.
 *
 *  - [[HumanVsHuman]]: both sides are controlled by human input (local or remote).
 *  - [[HumanVsAI]]: one side is human; the other is an AI engine.
 *  - [[AIVsAI]]: both sides are controlled by AI engines (analysis / self-play).
 */
enum SessionMode:
  case HumanVsHuman
  case HumanVsAI
  case AIVsAI
