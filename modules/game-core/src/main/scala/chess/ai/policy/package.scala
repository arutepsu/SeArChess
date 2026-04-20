/** AI difficulty and behaviour policies.
 *
 *  Responsibilities:
 *  - pure rules that translate application-level difficulty settings into
 *    engine configuration (e.g. search depth, time budget, opening book usage)
 *  - move-acceptance policies (e.g. reject moves that trivially lose material at Easy level)
 *
 *  Policies are pure functions — they do not call the AI engine directly.
 *  Engine calls are made by chess.application.ai.service via chess.application.port.ai.AiEngine.
 *
 *  Not yet populated.
 */
package chess.application.ai.policy
