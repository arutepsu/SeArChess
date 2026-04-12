/** Application use cases that read session state without mutating it.
 *
 *  Responsibilities:
 *  - query types for session reads (e.g. GetSessionState, ListActiveSessions)
 *  - query handlers that delegate to chess.application.session.model or port.repository
 *
 *  Not yet populated — session lifecycle is not modelled in the current codebase.
 */
package chess.application.query.session
