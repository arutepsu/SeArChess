# Event Module Ownership

Status: architecture cleanup  
Scope: event serialization, internal publishing, and Game -> History delivery

## Split

| Module | Ownership | Responsibility |
|---|---|---|
| `game-contract` | shared boundary | `AppEvent`, event publisher/serializer ports, session ids and boundary values |
| `game-event-contract` | boundary schema | JSON serialization for `docs/contracts/game-events-v1` |
| `apps/game-service/modules/eventing` | internal JVM plumbing | fan-out, no-op, and collecting event publishers |
| `apps/game-service/modules/history-delivery` | Game Service-owned delivery | SQLite History outbox, forwarder, direct HTTP fallback |

## Why

The old `adapter-event` module mixed event wire schema, in-process fan-out,
durable outbox storage, and History forwarding. Those pieces have different
owners:

- Event JSON is a boundary contract.
- Fan-out/no-op publishers are internal runtime plumbing.
- History outbox and forwarding are Game Service delivery infrastructure.

Splitting the modules makes those ownership lines visible in the build graph.

## Non-Goals

This cleanup does not change delivery semantics. It does not add Kafka, broker
abstractions, replay tooling, or new runtime infrastructure.
