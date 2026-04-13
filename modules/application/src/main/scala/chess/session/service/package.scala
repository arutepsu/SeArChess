/** Session lifecycle coordination services.
 *
 *  Responsibilities:
 *  - coordinating session state with UI observers (ObservableGame belongs here)
 *  - managing active session registration and state access
 *  - bridging between application use-case results and registered callbacks
 *
 *  Candidate migration: chess.application.ObservableGame → this package.
 *  ObservableGame is a session-lifecycle concern: it holds current GameState
 *  and notifies registered adapters — the observable coordination pattern is
 *  inherently a session service responsibility.
 *
 *  Services here may have managed mutable state (unlike policies which are pure).
 */
package chess.application.session.service
