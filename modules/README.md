# Module Dependency Overview

## Big picture

The architecture should follow this dependency shape:

clients / UIs / transports  
-> adapters  
-> game-core  
-> domain

And around that:

- `notation` provides parsing/format support as a separate capability
- `game-contract` provides small Game Service contract values shared with downstream services
- `game-service` assembles the runtime
- persistence/event/AI/websocket/rest adapters connect the outside world to Game core

## Core dependency direction

### Center of the system
- `domain` = core business model and rules
- `game-contract` = cross-service Game Service contract values
- `game-core` = Game Service use cases and orchestration on top of domain

### Around the center
- `adapter-rest-contract` = external REST schema
- `adapter-rest-http4s` = concrete HTTP transport adapter
- `adapter-websocket` = live event transport adapter
- `adapter-event` = shared event distribution adapter
- `adapter-persistence` = storage adapter
- `adapter-ai` = automated player adapter
- `adapter-gui` = desktop UI adapter
- `adapter-tui` = text UI adapter
- `web-ui` = browser UI client
- `notation` = PGN/FEN/JSON notation capability
- `game-service` = runtime composition root

## Recommended dependency map

- `domain`
  - depends on nothing outside itself

- `game-core`
  - depends on `domain`
  - depends on `game-contract`
  - depends on ports / stable boundaries
  - may depend on `notation.api` if notation is part of use cases

- `notation`
  - should stay independent from transport and persistence
  - may depend on `domain` only if truly needed for chess model integration
  - preferably exposes stable notation-facing APIs

- `adapter-rest-contract`
  - may depend on stable Game core/domain-facing data concepts carefully
  - must not depend on http4s
  - must not depend on runtime app wiring

- `adapter-rest-http4s`
  - depends on `adapter-rest-contract`
  - depends on Game core through the REST contract/route wiring path
  - depends on http4s libraries

- `adapter-websocket`
  - depends on Game core event boundaries or shared event contracts
  - depends on `adapter-event` or event-facing port

- `adapter-event`
  - depends on Game core event ports/contracts
  - should stay independent from HTTP/UI concerns

- `adapter-persistence`
  - depends on Game core repository ports
  - may depend on `domain` for persistence mapping
  - depends on storage technology

- `adapter-ai`
  - depends on Game core
  - may depend on `domain` for strategy reasoning
  - must not bypass Game core boundaries

- `adapter-gui`
  - depends on Game core or controller boundary
  - may depend on `domain` only for view-friendly read models if needed carefully

- `adapter-tui`
  - depends on Game core or controller boundary

- `web-ui`
  - depends on `adapter-rest-contract` as API contract
  - talks to backend over REST/WebSocket
  - does not depend on backend internals

- `game-service`
  - depends on many modules
  - wires them together
  - must sit at the top
  - no other module should depend on it

## ASCII view

```text
                 +----------------------+
                 |   game-service   |
                 | runtime composition  |
                 +----------+-----------+
                            |
        -------------------------------------------------
        |           |            |          |           |
        v           v            v          v           v
+---------------+ +-----------+ +--------+ +---------+ +-------------+
| rest-http4s   | | websocket | | event  | | AI      | | persistence |
+-------+-------+ +-----+-----+ +----+---+ +----+----+ +------+------+
        |               |            |          |             |
        v               |            |          |             |
+---------------+       |            |          |             |
| rest-contract |       |            |          |             |
+-------+-------+       |            |          |             |
        |               |            |          |             |
        ---------------- game-core ----------------------------
                           |
                           v
                         domain

Clients:
- web-ui -> rest-contract -> rest-http4s
- adapter-gui -> game-core
- adapter-tui -> game-core

Separate capability:
- notation -> used by Game core and/or selected adapters through stable APIs
```

## Main architectural rules

1. `domain` is the innermost stable core.
2. `game-core` is the Game Service-owned use-case entry layer for adapters.
3. Adapters depend inward on Game core/domain, not sideways on each other unless explicitly justified.
4. `adapter-rest-contract` is the stable backend API schema boundary.
5. `web-ui` consumes contracts, not backend internals.
6. `adapter-event` is the shared event backbone for live updates.
7. `game-service` assembles everything, but no one depends on it.

## Why this matters

This gives you:
- clear dependency direction
- replaceable adapters
- one source of truth for business rules
- a stable API boundary for the Web UI
- a clean path toward WebSocket live updates, Kafka, and later microservices

If this overview stays true, the module structure will scale instead of turning into cross-module coupling.
