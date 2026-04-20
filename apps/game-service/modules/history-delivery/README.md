# game-history-delivery

Scope: Game-owned delivery infrastructure for terminal Game events consumed by
History Service.

This module owns:

- `HistoryEventOutbox`
- `SqliteHistoryEventOutbox`
- `HistoryOutboxForwarder`
- `HistoryHttpEventPublisher`
- `DurableHistoryEventPublisher`

It is intentionally local/dev delivery infrastructure, not a broker framework.
It uses the event JSON serializer from `game-event-contract` and persists /
forwards terminal events to History.

The module does not define the wire schema; it only stores and delivers payloads
that already follow the Game event contract.
