# game-event-contract

Scope: Game event JSON wire serialization.

This module owns `AppEventSerializer`, the serializer for the versioned Game
event JSON contract documented in:

- `docs/contracts/game-events-v1.md`
- `docs/contracts/game-events-v1.json`

It depends on `game-contract`, not `game-core`. That keeps event wire
serialization separate from Game Service orchestration internals.

It does not own delivery. Publishing, outbox storage, retry, and HTTP forwarding
belong to the Game Service runtime/delivery modules.

The canonical History ingestion route metadata is
`GameHistoryIngestionContract.GameEventsPath`, currently
`/internal/events/game`. `LegacyGameEventsPath` exists only as a temporary
compatibility marker and is disabled by default in History Service startup.
