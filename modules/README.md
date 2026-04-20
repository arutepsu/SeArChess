# Shared Modules

This directory is now reserved for code that is intentionally shared across
services or clients.

## Modules

| Module | Ownership | Role |
|---|---|---|
| `domain` | shared library | Pure chess model and rules. |
| `notation` | shared library | FEN/PGN parsing and rendering. |
| `game-contract` | shared contract | Game-facing IDs, session metadata, game views, archive snapshots, and event ports used across service boundaries. |
| `game-event-contract` | shared boundary contract | Versioned Game event JSON serialization for downstream consumers such as History. |
| `adapter-rest-contract` | shared HTTP contract | Wire DTOs/codecs for the Game Service HTTP API. |

## What Does Not Belong Here

Service-owned runtime code should live with the service that owns it:

- Game Service code lives under `apps/game-service/modules`.
- History Service code lives under `apps/history-service/modules`.
- Desktop GUI adapter code lives under `apps/desktop-gui/modules`.
- TUI adapter code lives under `apps/tui-cli/modules`.

Package names are intentionally not renamed in this slice. The ownership change
is expressed first through the project and directory structure.
