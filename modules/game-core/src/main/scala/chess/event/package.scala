/** Application-layer event envelopes emitted by use cases.
 *
 *  Responsibilities:
 *  - application-level event types that wrap or augment domain events with
 *    application context (e.g. session id, timestamp, player identity)
 *  - distinct from chess.domain.event.DomainEvent — those are pure domain facts;
 *    application events carry orchestration-level context
 *
 *  Note: chess.application.EventBuilder currently produces domain events directly.
 *  If application-layer event envelopes are introduced, they should live here and
 *  be produced by command handlers after wrapping the domain events from EventBuilder.
 *
 *  Not yet populated.
 */
package chess.application.event
