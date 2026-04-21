package chess.application.port.event

import chess.application.event.AppEvent

/** Port for serialising terminal [[AppEvent]]s to their JSON wire representation
 *  before writing them to the durable outbox inside a persistence transaction.
 *
 *  Callers that must produce an outbox payload atomically with a state write
 *  (e.g. [[chess.application.session.service.SessionGameService]]) depend on
 *  this port rather than on [[chess.adapter.event.AppEventSerializer]] directly,
 *  keeping the application layer free of adapter imports.
 *
 *  [[NoOpTerminalEventJsonSerializer]] always returns [[None]] and is the
 *  correct implementation for in-memory persistence and GUI/TUI modes where no
 *  durable outbox is configured.  It is the default in all service constructors.
 */
trait TerminalEventJsonSerializer:
  def serialize(event: AppEvent): Option[String]

object NoOpTerminalEventJsonSerializer extends TerminalEventJsonSerializer:
  def serialize(event: AppEvent): Option[String] = None
