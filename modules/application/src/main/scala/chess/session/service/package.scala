/** Session service layer.
 *
 *  Owns application-level session orchestration, lifecycle transitions,
 *  and command/query boundaries.
 *
 *  It does NOT own desktop/UI notification state. Cross-adapter state
 *  notification is exposed via the `GameStateObservable` abstraction,
 *  while the concrete observable implementation belongs in the
 *  bootstrap-server composition layer.
 */
package chess.application.session.service
