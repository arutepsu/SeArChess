# adapter-rest-contract

`adapter-rest-contract` is the wire-level HTTP contract module for the Game
Service API.

It contains only:

- request DTOs
- response DTOs
- error DTOs
- JSON parsing / JSON rendering for those DTOs

It intentionally does not depend on `game-core`, `domain`, repository ports, or
application service/query models.

## Ownership Rule

This module describes what crosses the HTTP boundary. It does not know how the
Game Service implements that boundary.

Mapping between internal Game Service models and these DTOs is owned by the
Game Service HTTP adapter, currently:

```text
apps/game-service/modules/rest-http4s/src/main/scala/chess/adapter/http4s/mapper
```

That mapper package may import Game core/domain types because it is part of the
Game Service adapter layer. The contract module may not.

## Current DTOs

- `ArchiveSnapshotResponse`
- `CreateSessionRequest`
- `CreateSessionResponse`
- `ErrorResponse`
- `GameResponse`
- `LegalMovesResponse`
- `ResignRequest`
- `SessionListResponse`
- `SessionResponse`
- `SubmitMoveRequest`
- `SubmitMoveResponse`

## Dependency Shape

Desired direction:

```text
adapter-rest-contract  -> ujson only
apps/game-service/modules/rest-http4s -> adapter-rest-contract + game-core
apps/game-service                     -> rest-http4s
```

This keeps public HTTP shapes importable without also pulling in Game Service
internals.

## Non-Goals

This module does not:

- validate chess move legality
- construct domain commands
- inspect game state
- map repository/application errors to HTTP status codes
- mount routes or start servers
- define framework-specific http4s behavior

Those are Game Service adapter/runtime responsibilities.
