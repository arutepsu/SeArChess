# Game Service Modules

These modules are owned by the Game Service runtime.

| Module | Role |
|---|---|
| `core` | Game/session orchestration, active gameplay use cases, Game Service API, repository/event ports. |
| `persistence` | In-memory and SQLite active game/session persistence. |
| `rest-http4s` | Game Service HTTP route implementation. |
| `websocket` | Live Game event WebSocket transport. |
| `ai` | `AiMoveSuggestionClient` adapters: remote Python service client by default, local deterministic dev/test fallback. |
| `eventing` | Internal in-process event publisher utilities. |
| `history-delivery` | Game-owned durable outbox and HTTP forwarding to History Service. |

The shared wire contracts remain in root `modules/`:

- `modules/game-contract`
- `modules/game-event-contract`
- `modules/adapter-rest-contract`

This keeps the Game Service implementation visually close to its runnable app
while preserving explicit shared contracts.
