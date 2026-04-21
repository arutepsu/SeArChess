# Game Service Eventing

Scope: internal in-process event publisher utilities.

This module now owns only small `EventPublisher` implementations used inside
the JVM:

- `FanOutEventPublisher`
- `NoOpEventPublisher`
- `CollectingEventPublisher` for tests

It intentionally does not own:

- Game event JSON serialization
- Game -> History outbox persistence
- Game -> History HTTP forwarding

Those responsibilities live in focused modules:

- `modules/game-event-contract`
- `apps/game-service/modules/history-delivery`

This keeps internal event plumbing separate from cross-service boundary
serialization and Game-owned delivery infrastructure.
