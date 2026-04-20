# SeArChess

[![Coverage Status](https://coveralls.io/repos/github/arutepsu/SeArChess/badge.svg?branch=main)](https://coveralls.io/github/arutepsu/SeArChess?branch=main)

SeArChess is a chess system being evolved from a modular monolith into a small
set of service-oriented runtimes.

Current runnable services and clients:

- `apps/game-service`
- `apps/history-service`
- `apps/web-ui`
- `apps/desktop-gui`
- `apps/tui-cli`

The repository structure now makes service ownership the default way to read the
codebase. Shared contracts and reusable libraries stay in root `modules/`;
service-owned implementation modules live beside the app that owns them.

## Repository Shape

```text
apps/
  game-service/
    modules/
      ai/
      core/
      eventing/
      history-delivery/
      persistence/
      rest-http4s/
      websocket/
  history-service/
    modules/
      core/
  desktop-gui/
    modules/
      gui/
  tui-cli/
    modules/
      tui/
  startup-shared/
  web-ui/

modules/
  adapter-rest-contract/
  domain/
  game-contract/
  game-event-contract/
  notation/
```

See [docs/architecture/service-oriented-repository-structure.md](docs/architecture/service-oriented-repository-structure.md)
for the detailed ownership classification.

## Ownership Rules

1. Game Service owns active game/session orchestration, active persistence,
   HTTP/WebSocket adapters, AI integration, internal eventing, and History
   delivery.
2. History Service owns archive ingestion, materialization, and archive
   persistence.
3. Root `modules/` contains only shared contracts and reusable libraries.
4. Shared modules must not depend on service-owned implementation modules.
5. Services may depend on shared modules and their own implementation modules.
6. Package names are currently lower priority than module ownership; some
   packages still use legacy names such as `chess.application.*`.

## Key Contracts

- [Game Service HTTP](docs/contracts/game-service-http-v1.md)
- [History Service HTTP](docs/contracts/history-service-http-v1.md)
- [Game event JSON](docs/contracts/game-events-v1.md)
- AI inference API contract in the Python AI service repository

## Local Development

For the local two/three-container service proof, start with:

- [Container local guide](docs/dev-guide-container-local.md)
- [Game Service guide](docs/dev-guide-game-service.md)
- [History Service guide](docs/dev-guide-history-service.md)

## Build

Common SBT entry points are kept stable even though source directories moved:

```powershell
sbt "gameService/test"
sbt "historyService/test"
sbt "gameService / stage"
```

The project intentionally keeps this step structural only: no runtime behavior,
contracts, routes, persistence semantics, or delivery semantics are changed by
the service-oriented directory layout.
