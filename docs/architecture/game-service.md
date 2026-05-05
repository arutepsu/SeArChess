# Game Service Architecture Contract

## 1. Purpose and relationship to the game-service Blueprint

This document complements apps/game-service/README.md. The README describes game-service as the runtime composition root; this document defines the architectural contracts and responsibility boundaries of the game-service subsystem.

The existing Blueprint explains how the backend process is assembled at runtime: configuration, adapter selection, application wiring, interface composition, and lifecycle. This contract does not replace that document and does not duplicate its runtime composition detail. It defines the stable module, service, persistence, migration, HTTP, and dependency boundaries that runtime composition must respect.

## 2. Module contract map

| Module | Responsibility | Owns | Does not own | Allowed dependencies | Forbidden dependencies | Public contracts exposed |
|---|---|---|---|---|---|---|
| `apps/game-service/modules/core` | Game Service use cases, session/game orchestration, and outbound ports. | `GameServiceApi`, command/query application services, repository ports, AI/event ports, session policies, game-session models owned by the Game Service. | HTTP, WebSocket, database implementations, AI provider implementations, runtime backend selection, process startup. | Domain model/rules, shared contract values, pure application policies, port traits. | http4s, JDBC/Slick/Mongo driver types, concrete repositories, concrete AI clients, runtime app/root modules. | `GameServiceApi`, `GameSessionCommands`, `SessionLifecycleService`, repository ports, AI ports, event ports, session/game read models. |
| `apps/game-service/modules/persistence` | Infrastructure persistence adapters for Game Service storage ports. | In-memory, SQLite, Postgres, and Mongo repository/store implementations; DB-local schemas, mappers, JSON/document/row formats; contract-test implementations. | Use-case orchestration, transport behavior, route error mapping, runtime profile choice. | Core repository ports, domain/application values, database libraries inside adapter packages. | HTTP route modules, WebSocket modules, `game-service` app/root startup code, DB-specific types in core ports. | `SessionRepository`, `GameRepository`, `SessionGameStore` implementations; migration readers for storage backends. |
| `apps/game-service/modules/rest-http4s` | Concrete http4s transport adapter for Game Service REST endpoints. | Route definitions, request decoding, DTO mapping, HTTP status/error mapping, route composition. | Chess rules, persistence adapter selection, direct repository access, process startup. | `GameServiceApi`, `PersistentSessionService`, adapter REST contract DTOs, http4s. | Concrete repositories, database clients, hidden service construction, WebSocket implementation details. | http4s route groups and composed HTTP app. |
| `apps/game-service/modules/websocket` | Live event transport for clients. | WebSocket connection handling, subscription wiring, live delivery of application events. | Business mutation path, session/game state authority, persistence access. | Event stream/public event contracts, application command boundary only if future command channel is explicitly introduced. | Direct repositories, database adapters, duplicate move/session business logic. | WebSocket endpoint/route and event stream contract. |
| `apps/game-service/modules/ai` | AI provider adapters for `AiMoveSuggestionClient`. | Remote AI Service client, request/response mapping, local deterministic test/dev fallback. | AI turn orchestration policy, game persistence, move application authority. | Core AI port, AI DTO/client libraries, local deterministic helper logic. | Session repositories, `SessionGameStore`, route modules, direct game mutation outside `AITurnService`. | `AiMoveSuggestionClient` implementations. |
| `apps/game-service/modules/eventing` | In-process event publisher utilities. | No-op, collecting, and fan-out publisher implementations. | Event meaning, durable delivery guarantees, HTTP forwarding to History Service. | Core event port/contracts. | Repositories, routes, business orchestration. | `EventPublisher` implementations for local composition and tests. |
| `apps/game-service/modules/history-delivery` | Game-owned delivery adapter for terminal game history events. | Durable outbox model, HTTP forwarding to History Service, outbox forwarder behavior. | History Service archive storage/materialization, chess gameplay rules, normal HTTP API. | Core event contracts, history ingestion contract, local persistence needed for outbox. | Core use-case logic, direct ownership of History Service storage. | History event publisher/outbox implementations. |
| `apps/game-service/modules/migration` | DB-independent persistence migration application layer. | Migration modes, reports, conflict policy, source/target adapter ports, migration orchestration through repository/store ports. | Raw database copying, runtime DB construction, HTTP API, gameplay behavior. | Core repository ports and application values. | Slick/Mongo/JDBC types, concrete repository constructors, route modules. | `PersistenceMigrationService`, `SessionMigrationReader`, migration adapter models, reports. |
| `apps/game-service` app/root composition | Runtime composition root. | Config loading, backend selection, resource acquisition/release, concrete adapter construction, route/WebSocket mounting, server/CLI entrypoints. | Business rules, repository contracts, route-specific business logic, persistence implementation internals. | All Game Service modules and shared contracts needed to assemble one process. | Being imported by lower-level modules; becoming a second application layer. | Main/server/desktop/migration launch paths and assembled backend process. |

## 3. Core application service responsibility map

| Service | Current responsibility | Contract role | May call | Must not call | Used by | Future rename if recommended |
|---|---|---|---|---|---|---|
| `GameServiceApi` | Explicit public boundary for inbound Game Service operations. | Primary application facade contract for transports and future remote boundary. | No implementation calls; trait only. | Concrete repositories or adapters. | REST routes, future WebSocket command channel, future service clients. | Keep name. |
| `DefaultGameService` | Thin orchestration facade that routes commands/queries to existing services and maps some errors/events. | Default in-process implementation of `GameServiceApi`. | `GameSessionCommands`, `SessionLifecycleService`, `GameRepository`, `EventPublisher`, optional `AITurnService`. | Concrete repositories/adapters, database clients, route DTOs. | Runtime composition, REST routes through `GameServiceApi`. | `InProcessGameServiceApi` if a remote implementation is introduced. |
| `GameStateCommandService` | Application facade over pure game creation, move application, legal target checks, and promotion-pending detection. | Game-state application helper around domain rules. | `GameTransitionService`, `GameStateRules`, domain state factory. | Repositories, sessions, events, HTTP, AI providers. | `SessionLifecycleService`, tests, application logic. | `GameRulesApplicationService` or `GameStateCommandService`; current name sounds too top-level. |
| `GameTransitionService` | Applies a move to `GameState` and builds resulting domain/application move events. | Pure transition orchestration boundary. | `GameStateRules`, `EventBuilder`. | Session services, repositories, transport adapters. | `GameStateCommandService`. | Keep, or `MoveTransitionService` if the scope remains move-only. |
| `SessionLifecycleService` | Session lifecycle, session-only persistence, controller checks, and pure session-aware move computation. | Session lifecycle and policy application service. | `SessionRepository`, `EventPublisher`, `GameStateCommandService`, lifecycle/control policies. | `GameRepository` for gameplay commands, `SessionGameStore` for combined writes, routes. | `DefaultGameService`, `SessionGameCommandService`, `PersistentSessionService`. | `SessionLifecycleService` once command responsibilities are narrowed. |
| `SessionGameCommandService` | Authoritative session-aware command implementation for new games, moves, and resignation. Owns combined session + game-state persistence through `SessionGameStore` and post-persistence event publication. | Pure implementation of `GameSessionCommands`; write authority for gameplay. | `SessionLifecycleService` (for move application and lifecycle transitions), `SessionGameStore`, `EventPublisher`, terminal serializer. | Direct `SessionRepository`/`GameRepository` writes for combined commands, routes, DB adapters. | `DefaultGameService`, `AITurnService`, tests. | Keep. |
| `GameSessionCommands` | Primary port for state-changing session/game commands. | Extractable command-service contract. | Trait only. | Query/read concerns, AI provider logic, DB types. | `DefaultGameService`, `AITurnService`, future adapters. | Keep. |
| `PersistentSessionService` | Persistence/resume aggregate flows: list active sessions, load aggregate, save aggregate, cancel via existing lifecycle service. | Application service for persisted aggregate snapshots, not normal gameplay. | `SessionRepository`, `GameRepository`, `SessionGameStore`, `SessionLifecycleService`. | Chess move application, direct adapter/DB types, normal move endpoint behavior. | HTTP persistence/resume endpoints. | `SessionSnapshotService` or `SessionResumeService`. |
| `AITurnService` | Orchestrates one AI-controlled move through the normal command boundary. | AI turn application service. | `AiMoveSuggestionClient`, `GameSessionCommands`, `EventPublisher`, AI policy, legal-move validation. | Repositories, concrete AI HTTP client types outside the port, direct game persistence. | `DefaultGameService`, tests. | Keep. |
| `PersistenceMigrationService` | Reads source sessions/games, compares target state, and optionally writes missing aggregates through `SessionGameStore`. | DB-independent migration use-case service. | `MigrationSourceAdapter`, `MigrationTargetAdapter`, repository ports, `SessionGameStore`. | Raw DB copy logic, concrete DB drivers, HTTP routes. | Migration CLI/runtime factories and migration tests. | Keep. |

## 4. Persistence contracts

| Port | Responsibility | Operations | Guarantees | Must not do | Implementations | Contract tests |
|---|---|---|---|---|---|---|
| `SessionRepository` | Persist and retrieve session metadata. | `save`, `load`, `loadByGameId`, `listActive`, `saveCancelWithOutbox`. | Upsert by `SessionId`; lookup by `SessionId` and `GameId`; duplicate game ownership is a conflict; active listing excludes terminal lifecycles; storage errors use `RepositoryError`. | Store board state, own game snapshots, expose DB rows/documents, decide runtime backend. | In-memory, SQLite, Postgres, Mongo. | `SessionRepositoryContract` and implementation specs. |
| `GameRepository` | Persist and retrieve current authoritative `GameState` snapshots. | `save`, `load`. | Save/load round-trips rule-critical game state; repeated saves replace current snapshot; missing game returns `NotFound`. | Store session lifecycle/controller metadata, archive/search history, DB-specific public formats. | In-memory, SQLite, Postgres, Mongo. | `GameRepositoryContract` and implementation specs. |
| `SessionGameStore` | Coordinate writes where session metadata and game state must become visible together. | `save`, `saveTerminal`. | After `Right(())`, both session and game state are visible through normal repositories; conflicts surface as `RepositoryError`; terminal save may include outbox payloads where supported. | Provide read APIs, become a generic transaction framework, leak DB transactions or driver types, bypass repositories for application semantics. | In-memory, SQLite, Postgres, Mongo. | `SessionGameStoreContract` and implementation specs. |
| `RepositoryError` | Shared repository error vocabulary. | `NotFound`, `Conflict`, `StorageFailure`. | Application services can handle repository failures without catching adapter exceptions. | Encode HTTP status codes, DB exception types, or service-specific errors. | Defined in core; returned by all repository ports. | Covered indirectly by repository/service tests. |

Postgres `SessionGameStore` uses real database transactions for coordinated session/game writes. SQLite also has transactional behavior for the currently supported local/outbox paths where implemented. Mongo `SessionGameStore` is documented as best-effort and weaker unless backed by an explicit Mongo transaction setup; it must not be described as equivalent to the relational transaction guarantee. In-memory persistence is for tests and local runtime only; it is process-local, non-durable, and not a production consistency guarantee.

## 5. Migration contracts

| Contract | Responsibility |
|---|---|
| `SessionMigrationReader` | Read source sessions in batches with an optional cursor. It is separate from `SessionRepository` because migration traversal is an operational concern, not a normal application lookup. |
| `MigrationSourceAdapter` | Names a source and exposes its `SessionMigrationReader` plus `GameRepository`. |
| `MigrationTargetAdapter` | Names a target and exposes target `SessionRepository`, `GameRepository`, and `SessionGameStore`. |
| `PersistenceMigrationService` | Runs the DB-independent migration algorithm: read, load source state, compare target, report, and optionally write through `SessionGameStore`. |
| `MigrationMode` | Defines `DryRun`, `Execute`, and `ValidateOnly`. |
| `MigrationConflictPolicy` | Defines how existing target aggregates are treated. Current policy is `SkipEquivalentElseConflict`. |
| `MigrationReport` | Summarizes run metadata, counts, item-level results, and fatal failures. |
| `MigrationRunId` | Stable identifier for an individual migration report/run. |

`DryRun` reports what would be migrated without writing. `Execute` writes missing target aggregates through `SessionGameStore`. `ValidateOnly` treats missing or different target aggregates as validation mismatches and does not write.

The idempotency policy is: re-running against an already equivalent target must skip or validate equivalent aggregates rather than rewriting them. Existing target aggregates that differ from the source are conflicts under the current policy. Target writes must go through `SessionGameStore`; the migration service must not implement raw table/document copy logic and must remain independent of Postgres, Mongo, SQLite, or in-memory implementation details.

The CLI is an operational entrypoint that selects concrete source/target adapters, runs `PersistenceMigrationService`, and prints text or JSON output. CLI parsing, config, exit code mapping, and report formatting belong to the app/root migration runtime, not to the migration service contract.

## 6. HTTP/Web UI persistence contract

| Endpoint | Purpose | Application service used | Error mapping | Path type |
|---|---|---|---|---|
| `POST /sessions` | Create a new playable session with initial game state. | `GameServiceApi.createGame`. | Bad request for invalid body/mode/controller input; storage/application failures mapped by route support. | Normal gameplay/session creation path. |
| `GET /sessions` | List active/resumable sessions. | `GameServiceApi.listActiveSessions`. | Internal error for storage failure; empty list is success. | Persistence/resume query path. |
| `GET /sessions/{sessionId}/state` | Load the full persisted session + game-state aggregate. | `PersistentSessionService.loadAggregate`. | `400` invalid UUID, `404` missing session, `409` conflicts where applicable, `500` aggregate inconsistency/storage failure. | Persistence/resume snapshot path. |
| `PUT /sessions/{sessionId}/state` | Replace a persisted session + game-state aggregate through the coordinated write boundary. | `PersistentSessionService.saveAggregate`. | `400` invalid UUID/body or path/body mismatch, `404` missing target session, `409` conflict, `500` aggregate inconsistency/storage failure. | Persistence snapshot endpoint, not normal gameplay. |
| `POST /sessions/{sessionId}/cancel` | Administratively cancel a session. | `GameServiceApi.cancelSession`. | `400` invalid UUID, `404` missing session, `409` already terminal/invalid lifecycle, `500` storage failure. | Administrative lifecycle path. |

Normal gameplay uses command endpoints such as create game, submit move, resign, and AI move trigger. `PUT /sessions/{id}/state` is a persistence snapshot endpoint, not the normal move path. The Web UI must not know whether the backend uses Postgres, Mongo, SQLite, or in-memory storage; it speaks only HTTP DTOs and receives HTTP errors.

Routes must use application services. They must not call repositories directly and must not construct persistence adapters.

## 7. Dependency rules

1. Core must not depend on adapters.
2. Adapters may depend on core ports and application contracts.
3. Routes must not call repositories directly.
4. Persistence adapters must not expose DB-specific types through ports.
5. Migration service must remain DB-independent.
6. Migration writes must go through `SessionGameStore`.
7. Backend selection belongs to composition/config.
8. WebSocket must not become a second business path.
9. AI adapters must implement `AiMoveSuggestionClient`; AI moves must still pass through `AITurnService` and `GameSessionCommands`.
10. Eventing adapters distribute application events; they do not define gameplay semantics.
11. History delivery forwards terminal game information; it does not own History Service archive storage.
12. The app/root may depend downward on modules; modules must not depend upward on the app/root.

## 8. Known naming and package ambiguities

| Issue | Current status | Architectural decision | Future target name/structure |
|---|---|---|---|
| `GameStateCommandService` name sounds like the top-level service. | It is a game-state application helper over domain rules, not the public Game Service boundary. | `GameServiceApi` is the top-level application boundary for inbound adapters. | Rename to `GameStateCommandService`, `GameRulesApplicationService`, or similar when package cleanup is scheduled. |
| `SessionLifecycleService` vs `SessionGameCommandService` vs `PersistentSessionService`. | Names overlap: lifecycle, gameplay commands, and snapshot/resume flows are all session-related. | Treat `SessionLifecycleService` as lifecycle/policy, `SessionGameCommandService` as command write authority, and `PersistentSessionService` as snapshot/resume aggregate service. | `SessionLifecycleService`, `SessionGameCommandService`, and `SessionSnapshotService` or `SessionResumeService`. |
| Physical folder/package drift. | Some packages still use older `chess.application.*` or adapter names while files live under Game Service-owned modules. | Current ownership is module-based; package cleanup is deferred to reduce behavioral risk. | Align physical folders and packages around `game-service` module ownership in a mechanical refactor. |
| Command/query package maturity. | Command and query packages exist but are not yet a fully separated CQRS structure. | Keep command/query boundaries pragmatic; do not introduce ceremony before responsibilities require it. | Clarify command services, query read models, and facade routing after service names are narrowed. |

## 9. Refactor roadmap

| Phase | Goal | Risk level | Tests to run | Why not required for current behavior |
|---|---|---|---|---|
| Phase A: align folders/packages | Reduce physical folder/package drift and make ownership visible. | Medium because imports and build definitions can be touched widely. | `sbt "gameService/test" "adapterPersistence/test" "adapterRestHttp4s/test"` plus compile for affected modules. | Current behavior is already governed by module boundaries and tests; names are confusing but functional. |
| Phase B: rename misleading services | Make `GameStateCommandService`, `SessionLifecycleService`, `SessionGameCommandService`, and `PersistentSessionService` names reflect their roles. | Medium because references are broad but mostly mechanical. | Core, persistence, REST, and AI service tests. | Existing names are ambiguous but documented and do not change runtime semantics. |
| Phase C: narrow `SessionGameCommandService` responsibilities | ✅ Done. Removed query/lifecycle delegation from `SessionGameCommandService`; it now purely implements `GameSessionCommands`. Callers that needed lifecycle/query operations already depended on `SessionLifecycleService` directly (`DefaultGameService`, `GameController`, wiring). Test fixtures updated to call `SessionLifecycleService.createSession` directly. | — | — | — |
| Phase D: clarify command/query structure | Make command services, query services, and facade routing explicit. | Medium because public application service dependencies may change. | Full `gameService`, `adapterRestHttp4s`, and persistence test suites. | Current `GameServiceApi` already gives transports a stable boundary. |
| Phase E: split `DefaultGameService` only if it grows further | Keep the public facade thin or split into smaller command/query facades if it stops being a router. | Low now, higher only if growth continues. | Facade tests, route tests, AI tests, archive snapshot tests. | Today it is mostly a routing facade; splitting would add structure without immediate behavioral value. |

## 10. Stable decisions that must not change

- Repository ports stay.
- Migration reader stays separate from `SessionRepository`.
- `SessionGameStore` remains the consistency boundary for session + game-state writes.
- Adapters stay outside core.
- No database-specific HTTP endpoints.
- Migration stays an internal CLI unless an operational need requires a service/API.
- Runtime backend selection stays in composition/config.
- Web UI and WebSocket clients must not know concrete storage technology.
- Normal gameplay mutations stay behind application command services.

## 11. Package boundary notes

These notes were previously held in documentation-only `package.scala` files inside `core`. They are recorded here so the source files can be removed without losing the architectural intent.

| Package | Note |
|---|---|
| `chess.application.command.game` | Current command boundary: `GameSessionCommands` (trait) and `SessionGameCommandService` (implementation) are the first extractable service boundary candidates. The package is currently empty; it will hold explicit command types and handlers when the command surface is extracted. |
| `chess.application.command.session` | Forward boundary for session lifecycle command types (e.g. `CreateSession`, `ResignGame`, `ClaimDraw`) when multi-session or networked play is introduced. Not yet populated. |
| `chess.application.query.game` | Future migration candidate: `GameStateCommandService.legalTargetsFrom` → a `LegalTargetsQuery` handler in this package. This would move the read-only legal-move query out of the command service and into a dedicated query handler without changing the application API. |
| `chess.application.session.service` | This package owns session orchestration, lifecycle transitions, and command/query boundaries. It does **not** own desktop/UI notification state. Cross-adapter state notification is exposed via `GameStateObservable`; the concrete observable implementation belongs in the game-service composition layer. |

## 12. Reviewer summary

The architecture is professional because it has stable ports, isolated adapters, and an explicit composition root. Core application code depends on repository and AI/event ports rather than concrete infrastructure. Persistence behavior is defined by contracts and contract tests, with `SessionGameStore` documenting the consistency boundary for commands that change both session metadata and game state.

Migration is implemented through ports rather than raw database copying, so operational migration logic remains independent of storage technology. HTTP/Web UI persistence endpoints go through application services, including `PersistentSessionService` for snapshot/resume flows, and normal gameplay remains on command endpoints. The current limitations are documented honestly: some names are misleading, package/folder alignment is not finished, and Mongo coordinated-write semantics are weaker than the relational transaction path unless explicitly configured for transactions.
