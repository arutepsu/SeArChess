/** Session lifecycle policies.
  *
  * Responsibilities:
  *   - rules that govern who may join a session, when a session may be abandoned, rematch
  *     eligibility, timeout behaviour
  *   - pure decision functions that take session model state and return allow/deny outcomes
  *
  * Policies are pure functions — they do not coordinate I/O or mutate state. Policy decisions are
  * enforced by command handlers in chess.application.command.session.
  *
  * Not yet populated.
  */
package chess.application.session.policy
